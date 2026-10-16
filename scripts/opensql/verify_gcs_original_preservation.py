#!/usr/bin/env python3
"""Audit a private GCS copy of document originals without publishing object identities.

Storage Transfer Service owns the copy. This tool compares its source and
destination bytes, and writes the object-level ledger only to private local files.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


GCS_URI = re.compile(r"^gs://[a-z0-9][a-z0-9.-]+(?:/[A-Za-z0-9_./-]+)?$")


class AuditError(Exception):
    """Carry only a safe step name across the audit boundary."""

    def __init__(self, step):
        super().__init__(step)
        self.step = step


def run(command, step, timeout=3600):
    """Discard CLI output on failure because it can contain private object keys."""
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise AuditError(step) from error
    if result.returncode:
        raise AuditError(step)
    return result.stdout


def load_config(path):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    source = data["source"].rstrip("/")
    destination = data["destination"].rstrip("/")
    if not GCS_URI.fullmatch(source) or not GCS_URI.fullmatch(destination):
        raise AuditError("config_location")
    source_bucket, *source_path = source[5:].split("/", 1)
    destination_bucket, *destination_path = destination[5:].split("/", 1)
    paths = source_path + destination_path
    if source_bucket == destination_bucket or any(
        part in ("", ".", "..") for value in paths for part in value.split("/")
    ):
        raise AuditError("config_location")
    return {
        "source": source,
        "destination": destination,
        "manifest": Path(data["manifest"]),
        "status": Path(data["status"]),
    }


def write_private_json(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(value, output, sort_keys=True)
        output.write("\n")
    temporary.replace(path)


def parse_listing(output, root, invalid_keys=None):
    """Read gsutil's object headers and required metadata without logging names."""
    objects = {}
    key = None
    fields = {}

    def finish():
        if key is None:
            return
        if "Content-Length" not in fields or not fields.get("Generation"):
            raise AuditError("metadata_missing")
        objects[key] = {
            "size": int(fields["Content-Length"]),
            "generation": fields["Generation"],
        }

    for line in output.splitlines():
        if line.startswith("gs://") and line.endswith(":"):
            finish()
            uri = line[:-1]
            if not uri.startswith(root + "/"):
                raise AuditError("metadata_location")
            key = uri[len(root) + 1:]
            parts = key.split("/")
            if not key or any(part in ("", ".", "..") for part in parts):
                if invalid_keys is None:
                    raise AuditError("metadata_key")
                invalid_keys.append(key)
                key = None
            fields = {}
        elif key is not None and ":" in line:
            name, value = line.strip().split(":", 1)
            fields[name] = value.strip()
    finish()
    return objects


def download_objects(inventory, root, directory, step):
    """Fetch only validated object keys, keeping unrelated backup paths outside the audit."""
    def download(key):
        target = directory / key
        target.parent.mkdir(parents=True, exist_ok=True)
        # Pin the listed generation so a replacement cannot silently change the bytes during this run.
        uri = root + "/" + key + "#" + inventory[key]["generation"]
        run(["gcloud", "storage", "cp", uri, str(target), "--quiet"], step, timeout=300)

    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(download, key) for key in inventory]
        completed = 0
        for future in as_completed(futures):
            future.result()
            completed += 1
            if completed % 20 == 0 or completed == len(futures):
                print(json.dumps({"phase": step, "completed": completed, "total": len(futures)}), flush=True)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def unchanged_record(key, source_meta, destination_meta, previous):
    """Reuse a previously hashed immutable GCS generation only when both sides still match."""
    old = previous.get(key)
    if not old:
        return None
    if source_meta is not None and old.get("source_generation") != source_meta["generation"]:
        raise AuditError("source_generation_changed")
    if old.get("destination_generation") != destination_meta["generation"]:
        raise AuditError("destination_generation_changed")
    if old.get("size") != destination_meta["size"] or (
        source_meta is not None and old["size"] != source_meta["size"]
    ):
        raise AuditError("previous_size_changed")
    if not re.fullmatch(r"[0-9a-f]{64}", old.get("sha256", "")):
        raise AuditError("previous_hash_missing")
    return old


def select_downloads(source, destination, previous):
    """Only new generations need byte downloads; vanished verified copies are errors."""
    if previous.keys() - destination.keys():
        raise AuditError("previous_copy_missing")
    source_new = {}
    destination_new = {}
    for key, source_meta in source.items():
        destination_meta = destination.get(key)
        if destination_meta is None:
            raise AuditError("missing_copy")
        if source_meta["size"] != destination_meta["size"]:
            raise AuditError("size_mismatch")
        if unchanged_record(key, source_meta, destination_meta, previous) is None:
            source_new[key] = source_meta
            destination_new[key] = destination_meta
    for key in destination.keys() - source.keys():
        if unchanged_record(key, None, destination[key], previous) is None:
            destination_new[key] = destination[key]
    return source_new, destination_new


def compare(source, destination, source_dir, destination_dir, previous):
    """Reject missing or changed copies; retain known deleted-source originals."""
    if previous.keys() - destination.keys():
        raise AuditError("previous_copy_missing")
    records = {}
    for key, source_meta in source.items():
        destination_meta = destination.get(key)
        if destination_meta is None:
            raise AuditError("missing_copy")
        if source_meta["size"] != destination_meta["size"]:
            raise AuditError("size_mismatch")
        old = unchanged_record(key, source_meta, destination_meta, previous)
        if old:
            source_hash = old["sha256"]
        else:
            source_file = source_dir / key
            destination_file = destination_dir / key
            if source_file.stat().st_size != source_meta["size"] or destination_file.stat().st_size != destination_meta["size"]:
                raise AuditError("download_size")
            source_hash = sha256(source_file)
            if source_hash != sha256(destination_file):
                raise AuditError("sha256_mismatch")
        records[key] = {
            "source_generation": source_meta["generation"],
            "destination_generation": destination_meta["generation"],
            "size": source_meta["size"],
            "sha256": source_hash,
        }

    retained = 0
    unattributed = 0
    for key in destination.keys() - source.keys():
        destination_meta = destination[key]
        old = unchanged_record(key, None, destination_meta, previous)
        if old:
            destination_hash = old["sha256"]
        else:
            destination_file = destination_dir / key
            if destination_file.stat().st_size != destination_meta["size"]:
                raise AuditError("retained_size")
            destination_hash = sha256(destination_file)
        records[key] = {
            "source_generation": old["source_generation"] if old else None,
            "destination_generation": destination_meta["generation"],
            "size": destination_meta["size"],
            "sha256": destination_hash,
        }
        retained += 1
        unattributed += old is None
    return records, {"source": len(source), "destination": len(destination),
                     "retained": retained, "unattributed_retained": unattributed}


def audit(config):
    config["status"].parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (config["status"].parent / ".gcs-original-audit.lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        previous_status = {}
        if config["status"].exists():
            previous_status = json.loads(config["status"].read_text(encoding="utf-8"))
        try:
            # 1. Inventory both locations before downloading only validated keys.
            source = parse_listing(run(["gsutil", "ls", "-L", "-r", config["source"] + "/**"], "source_list"), config["source"])
            invalid_destination = []
            destination = parse_listing(run(["gsutil", "ls", "-L", "-r", config["destination"] + "/**"], "destination_list"), config["destination"], invalid_destination)
            if not source or not destination:
                raise AuditError("empty_inventory")
            previous = {}
            if config["manifest"].exists():
                previous = json.loads(config["manifest"].read_text(encoding="utf-8"))["objects"]
            # 2. Verify immutable generations against the last hashed ledger, then fetch only new bytes.
            source_new, destination_new = select_downloads(source, destination, previous)
            with tempfile.TemporaryDirectory() as temporary:
                source_dir = Path(temporary) / "source"
                destination_dir = Path(temporary) / "destination"
                download_objects(source_new, config["source"], source_dir, "source_download")
                download_objects(destination_new, config["destination"], destination_dir, "destination_download")
                # 3. Compare bytes and generation continuity against the private ledger.
                records, counts = compare(source, destination, source_dir, destination_dir, previous)
                counts["ignored_invalid_destination"] = len(invalid_destination)
                counts["source_downloaded"] = len(source_new)
                counts["destination_downloaded"] = len(destination_new)
            now = datetime.now(timezone.utc).isoformat()
            # 4. Keep the object-level ledger private; print counts only.
            write_private_json(config["manifest"], {"checked_at_utc": now, "objects": records})
            write_private_json(config["status"], {"last_success_utc": now, "last_error": None, **counts})
            print(json.dumps({"result": "ok", **counts}))
        except (AuditError, OSError, ValueError, KeyError) as error:
            step = error.step if isinstance(error, AuditError) else "local_state"
            write_private_json(config["status"], {
                **previous_status, "last_error": step,
                "last_run_utc": datetime.now(timezone.utc).isoformat(),
            })
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    try:
        audit(load_config(args.config))
        return 0
    except (AuditError, OSError, ValueError, KeyError, BlockingIOError) as error:
        step = error.step if isinstance(error, AuditError) else "local_state"
        print(json.dumps({"result": "failed", "step": step}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
