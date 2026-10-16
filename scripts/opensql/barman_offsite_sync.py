#!/usr/bin/env python3
"""Preserve completed Barman exports and archived WALs outside the backup VM.

The script owns only the offsite copy. Barman still creates backups and receives
WAL locally; this script never changes PostgreSQL or Barman retention state.
"""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from datetime import datetime, timezone


BACKUP_ID = re.compile(r"^[0-9]{8}T[0-9]{6}$")
SERVER_NAME = re.compile(r"^[A-Za-z0-9_.-]+$")
EXPORT_NAME = re.compile(r"^backup-export-.*-[0-9a-f]{8}\.tar(?:\.gz|\.bz2|\.xz)?$")
WAL_NAME = re.compile(r"^(?:[0-9A-F]{24}(?:\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)$")
# gsutil rsync uses re.match on the relative path. Exclude everything except
# complete WAL segments, backup history files and timeline history files.
WAL_EXCLUDE = r"^(?!.*(?:[0-9A-F]{24}(?:\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)$).*"


class SyncError(Exception):
    """Represent a failed offsite step without propagating private command output."""

    def __init__(self, step):
        super().__init__(step)
        self.step = step


def run(command, step, *, allow_missing=False, timeout=1800):
    """Run a command while keeping paths, bucket names and credentials out of logs."""
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SyncError(step) from error
    if result.returncode == 0:
        return result.stdout
    if allow_missing and "No URLs matched" in result.stderr:
        return None
    raise SyncError(step)


def load_config(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    server = data["server"]
    destination = data["destination"].rstrip("/")
    if not SERVER_NAME.fullmatch(server):
        raise SyncError("config_server")
    if not re.fullmatch(r"gs://[a-z0-9][a-z0-9.-]+(?:/[A-Za-z0-9_/-]+)?", destination):
        raise SyncError("config_destination")
    return {
        "server": server,
        "destination": destination,
        "work_dir": Path(data["work_dir"]),
        "status_file": Path(data["status_file"]),
        "max_lag_seconds": int(data.get("max_lag_seconds", 900)),
    }


def write_status(path, status):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(status, output, sort_keys=True)
        output.write("\n")
    temporary.replace(path)


def barman_paths(server):
    output = run(["barman", "show-server", server], "barman_show_server", timeout=60)
    values = {}
    for line in output.splitlines():
        match = re.match(r"^\s*(basebackups_directory|wals_directory):\s*(.+?)\s*$", line)
        if match:
            values[match.group(1)] = Path(match.group(2))
    if set(values) != {"basebackups_directory", "wals_directory"}:
        raise SyncError("barman_paths")
    return values


def completed_backups(server):
    output = run(["barman", "list-backup", server, "--minimal"], "barman_list_backup", timeout=60)
    completed = []
    for line in output.splitlines():
        backup_id = line.split()[0]
        if not BACKUP_ID.fullmatch(backup_id):
            raise SyncError("backup_id")
        details = run(["barman", "show-backup", server, backup_id], "barman_show_backup", timeout=60)
        if re.search(r"^\s*Status\s*:\s*DONE\s*$", details, re.MULTILINE):
            completed.append(backup_id)
    return completed


def remote_metadata(uri):
    output = run(["gsutil", "stat", uri], "gcs_stat", allow_missing=True, timeout=90)
    if output is None:
        return None
    size = re.search(r"^\s*Content-Length:\s*([0-9]+)\s*$", output, re.MULTILINE)
    crc = re.search(r"^\s*Hash \(crc32c\):\s*(\S+)\s*$", output, re.MULTILINE)
    if not size or not crc:
        raise SyncError("gcs_metadata")
    return {"size": int(size.group(1)), "crc32c": crc.group(1)}


def local_crc32c(path):
    output = run(["gsutil", "hash", "-c", str(path)], "local_checksum", timeout=600)
    match = re.search(r"^\s*Hash \(crc32c\):\s*(\S+)\s*$", output, re.MULTILINE)
    if not match:
        raise SyncError("local_checksum_format")
    return match.group(1)


def stage_export(server, backup_id, work_dir):
    directory = work_dir / backup_id
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    prefix = f"backup-export-{server}-{backup_id}-"
    archives = [p for p in directory.iterdir() if p.is_file() and EXPORT_NAME.fullmatch(p.name)]
    if len(archives) > 1:
        raise SyncError("multiple_exports")
    if not archives:
        # 1. Barman exports only a DONE full backup with its catalog and required WAL.
        run(["barman", "export-backup", server, backup_id, str(directory)], "barman_export")
        archives = [p for p in directory.iterdir() if p.is_file() and EXPORT_NAME.fullmatch(p.name)]
    if len(archives) != 1:
        raise SyncError("export_missing")
    archive = archives[0]
    if not archive.name.startswith(prefix):
        raise SyncError("export_identity")
    checksum = hashlib.sha256()
    with archive.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
    # Barman embeds the archive checksum in the filename; an interrupted export
    # must never be treated as a resumable completed export.
    filename_checksum = archive.name.rsplit("-", 1)[-1].split(".", 1)[0]
    if checksum.hexdigest()[:8] != filename_checksum:
        raise SyncError("export_checksum")
    return archive


def sync_backup(config, backup_id):
    destination = config["destination"] + "/exports/" + backup_id
    marker_uri = destination + "/complete.json"
    marker = run(["gsutil", "cat", marker_uri], "gcs_marker", allow_missing=True, timeout=90)
    if marker is not None:
        try:
            recorded = json.loads(marker)
            name = recorded["archive"]
            if not EXPORT_NAME.fullmatch(name) or not name.startswith(
                f"backup-export-{config['server']}-{backup_id}-"
            ):
                raise ValueError("invalid archive name")
            actual = remote_metadata(destination + "/" + name)
            if actual != {"size": recorded["size"], "crc32c": recorded["crc32c"]}:
                raise SyncError("archive_integrity")
            stage = config["work_dir"] / backup_id
            if stage.is_dir():
                for old in stage.iterdir():
                    if old.is_file() and (EXPORT_NAME.fullmatch(old.name) or old.name == "complete.json"):
                        old.unlink()
            return
        except (KeyError, ValueError, TypeError) as error:
            raise SyncError("marker_invalid") from error

    archive = stage_export(config["server"], backup_id, config["work_dir"])
    archive_uri = destination + "/" + archive.name
    # 2. The export filename is Barman's integrity contract; never rename it.
    expected = {"size": archive.stat().st_size, "crc32c": local_crc32c(archive)}
    existing = remote_metadata(archive_uri)
    if existing is not None and existing != expected:
        raise SyncError("archive_integrity")
    if existing is None:
        run(["gsutil", "-q", "cp", str(archive), archive_uri], "archive_upload")
    if remote_metadata(archive_uri) != expected:
        raise SyncError("archive_integrity")
    marker_path = archive.parent / "complete.json"
    marker_path.write_text(json.dumps({"archive": archive.name, **expected}), encoding="utf-8")
    marker_path.chmod(0o600)
    # 3. Publish the marker only after the uploaded object matches the local checksum.
    run(["gsutil", "-q", "cp", str(marker_path), marker_uri], "marker_upload")
    if run(["gsutil", "cat", marker_uri], "marker_readback", timeout=90) != marker_path.read_text(encoding="utf-8"):
        raise SyncError("marker_readback")
    archive.unlink()
    marker_path.unlink()


def sync_wals(config, directory):
    if not directory.is_dir():
        raise SyncError("wals_missing")
    wal_files = [p for p in directory.rglob("*") if p.is_file() and WAL_NAME.fullmatch(p.name)]
    # 1. Copy only locally archived files; a streaming .partial is not recoverable WAL.
    run(
        ["gsutil", "-m", "rsync", "-r", "-c", "-x", WAL_EXCLUDE,
         str(directory), config["destination"] + "/wals"],
        "wal_sync",
    )
    return len(wal_files)


def sync(config):
    config["work_dir"].mkdir(mode=0o700, parents=True, exist_ok=True)
    lock_path = config["work_dir"] / ".sync.lock"
    with lock_path.open("w", encoding="utf-8") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        previous = {}
        if config["status_file"].exists():
            previous = json.loads(config["status_file"].read_text(encoding="utf-8"))
        try:
            paths = barman_paths(config["server"])
            backup_ids = completed_backups(config["server"])
            if not backup_ids:
                raise SyncError("no_completed_backup")
            # 2. WAL goes first so a large base export cannot delay the ongoing stream.
            wal_count = sync_wals(config, paths["wals_directory"])
            for backup_id in backup_ids:
                sync_backup(config, backup_id)
            now = datetime.now(timezone.utc).isoformat()
            write_status(config["status_file"], {
                "last_success_utc": now,
                "last_run_utc": now,
                "last_error": None,
                "completed_backups": len(backup_ids),
                "local_wal_files": wal_count,
            })
            print(json.dumps({"result": "ok", "completed_backups": len(backup_ids), "local_wal_files": wal_count}))
        except (SyncError, OSError, ValueError) as error:
            # 3. Preserve the last success timestamp; the next timer run retries.
            step = error.step if isinstance(error, SyncError) else "local_state"
            write_status(config["status_file"], {
                **previous,
                "last_run_utc": datetime.now(timezone.utc).isoformat(),
                "last_error": step,
            })
            raise


def check_health(config):
    try:
        state = json.loads(config["status_file"].read_text(encoding="utf-8"))
        last = datetime.fromisoformat(state["last_success_utc"])
        age = (datetime.now(timezone.utc) - last).total_seconds()
        healthy = age <= config["max_lag_seconds"] and state.get("last_error") is None
    except (OSError, KeyError, ValueError, TypeError):
        age = -1
        healthy = False
    print(json.dumps({"healthy": healthy, "age_seconds": round(age) if age >= 0 else None}))
    return 0 if healthy else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["sync", "check"])
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        if args.action == "check":
            return check_health(config)
        sync(config)
        return 0
    except (SyncError, OSError, ValueError, KeyError, BlockingIOError) as error:
        step = error.step if isinstance(error, SyncError) else "local_state"
        print(json.dumps({"result": "failed", "step": step}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
