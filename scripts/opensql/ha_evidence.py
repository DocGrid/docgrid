#!/usr/bin/env python3
"""Keep an append-only, database-external ledger for OpenSQL HA experiments."""

from __future__ import annotations

import argparse
import csv
import fcntl
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
LABEL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}\Z")
EVENTS = "events.jsonl"
MANIFEST = "manifest.json"
SUMMARY = "summary.json"
REQUESTS = "requests.csv"


class EvidenceError(ValueError):
    """Reject ambiguous or inconsistent evidence before it can be summarized."""


def utc_now():
    """Return one timezone-explicit UTC instant."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def write_private(path, content, *, exclusive=False):
    """Write UTF-8 evidence with owner-only permissions."""
    flags = os.O_WRONLY | os.O_CREAT | (os.O_EXCL if exclusive else os.O_TRUNC)
    descriptor = os.open(path, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(content)
        output.flush()
        os.fsync(output.fileno())


def replace_private(path, content):
    """Replace a derived artifact atomically, keeping its contents owner-only."""
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def json_text(value):
    """Serialize evidence deterministically for byte-for-byte verification."""
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def read_manifest(directory):
    """Load immutable run metadata without accepting a different schema."""
    try:
        manifest = json.loads((directory / MANIFEST).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceError("manifest.json을 읽을 수 없습니다") from error
    if manifest.get("schema_version") != 1 or not manifest.get("run_id"):
        raise EvidenceError("manifest.json 형식이 올바르지 않습니다")
    return manifest


@contextmanager
def locked_events(directory, *, shared=False):
    """Serialize concurrent writers so each event remains one durable JSONL line."""
    try:
        with (directory / EVENTS).open("r+", encoding="utf-8") as output:
            fcntl.flock(output.fileno(), fcntl.LOCK_SH if shared else fcntl.LOCK_EX)
            try:
                output.seek(0)
                yield output
            finally:
                fcntl.flock(output.fileno(), fcntl.LOCK_UN)
    except OSError as error:
        raise EvidenceError("events.jsonl을 읽거나 잠글 수 없습니다") from error


def read_events(output):
    """Parse every append-only event; a torn or edited line invalidates the run."""
    output.seek(0)
    events = []
    for line_number, line in enumerate(output, 1):
        try:
            event = json.loads(line)
        except json.JSONDecodeError as error:
            raise EvidenceError(f"events.jsonl {line_number}행이 손상되었습니다") from error
        if not isinstance(event, dict):
            raise EvidenceError(f"events.jsonl {line_number}행은 객체여야 합니다")
        events.append(event)
    return events


def replay(manifest, events):
    """Derive request outcomes solely from the external event sequence."""
    requests = {}
    faults = []
    open_faults = set()
    finished_at = None
    event_ids = set()
    for event in events:
        event_id = event.get("event_id")
        if not isinstance(event_id, str) or event_id in event_ids:
            raise EvidenceError("event_id가 없거나 중복됩니다")
        event_ids.add(event_id)
        if event.get("run_id") != manifest["run_id"]:
            raise EvidenceError("서로 다른 run_id가 섞였습니다")
        at = event.get("at")
        try:
            parsed_at = datetime.fromisoformat(at.replace("Z", "+00:00"))
            if parsed_at.tzinfo is None:
                raise ValueError("timezone missing")
        except (AttributeError, ValueError) as error:
            raise EvidenceError("UTC 시각이 올바르지 않습니다") from error
        if finished_at is not None:
            raise EvidenceError("실험 종료 뒤 이벤트가 추가되었습니다")
        kind = event.get("kind")
        request_id = event.get("request_id")
        if kind == "sent":
            if not isinstance(request_id, str) or not LABEL.fullmatch(request_id) or request_id in requests:
                raise EvidenceError("request_id가 없거나 중복됩니다")
            if not isinstance(event.get("operation"), str) or not LABEL.fullmatch(event["operation"]):
                raise EvidenceError("operation이 필요합니다")
            requests[request_id] = {
                "request_id": request_id, "operation": event["operation"],
                "sent_at": at, "completed_at": "", "outcome": "UNKNOWN",
                "http_status": "", "reason": "no_terminal_event",
            }
        elif kind in ("acknowledged", "failed", "unknown"):
            if request_id not in requests or requests[request_id]["completed_at"]:
                raise EvidenceError("종결 이벤트에 대응하는 미완료 요청이 없습니다")
            status = event.get("http_status")
            if kind == "acknowledged" and (not isinstance(status, int) or not 200 <= status < 300):
                raise EvidenceError("성공 응답에는 2xx HTTP 상태가 필요합니다")
            if kind == "failed" and (not isinstance(status, int) or not 300 <= status < 600):
                raise EvidenceError("실패 응답에는 3xx~5xx HTTP 상태가 필요합니다")
            if kind == "unknown" and (status is not None or event.get("reason") not in
                                      {"timeout", "connection_lost", "client_stopped", "other"}):
                raise EvidenceError("결과 불명에는 허용된 사유만 기록합니다")
            row = requests[request_id]
            row["completed_at"] = at
            row["outcome"] = kind.upper()
            row["http_status"] = status if status is not None else ""
            row["reason"] = event.get("reason", "")
        elif kind == "fault":
            name, phase = event.get("name"), event.get("phase")
            if not isinstance(name, str) or not LABEL.fullmatch(name) or phase not in {"start", "end"}:
                raise EvidenceError("장애 이름 또는 단계가 올바르지 않습니다")
            if phase == "start":
                if name in open_faults:
                    raise EvidenceError("같은 장애가 이미 시작되었습니다")
                open_faults.add(name)
            elif name not in open_faults:
                raise EvidenceError("시작하지 않은 장애를 종료할 수 없습니다")
            else:
                open_faults.remove(name)
            faults.append({"at": at, "name": name, "phase": phase})
        elif kind == "finished":
            finished_at = at
        else:
            raise EvidenceError("알 수 없는 이벤트 종류입니다")
    counts = {name: sum(row["outcome"] == name for row in requests.values())
              for name in ("ACKNOWLEDGED", "FAILED", "UNKNOWN")}
    summary = {
        "schema_version": 1, "run_id": manifest["run_id"],
        "started_at": manifest["started_at"], "finished_at": finished_at,
        "request_count": len(requests), "outcome_counts": counts,
        "fault_events": faults, "open_faults": sorted(open_faults),
        "complete": finished_at is not None and not open_faults,
    }
    return summary, list(requests.values())


def csv_text(rows):
    """Export one row per request, including unacknowledged attempts."""
    fields = ("request_id", "operation", "sent_at", "completed_at", "outcome", "http_status", "reason")
    output = io.StringIO(newline="")
    writer = csv.DictWriter(output, fieldnames=fields, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue()


def append_event(directory, kind, **attributes):
    """Validate the whole ledger, then append and fsync exactly one event."""
    manifest = read_manifest(directory)
    event = {"event_id": str(uuid.uuid4()), "run_id": manifest["run_id"],
             "at": utc_now(), "kind": kind, **attributes}
    with locked_events(directory) as output:
        events = read_events(output)
        replay(manifest, events + [event])
        output.seek(0, os.SEEK_END)
        output.write(json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n")
        output.flush()
        os.fsync(output.fileno())
    return event


def initialize(args):
    """Start a fresh run without copying configuration contents or credentials."""
    if not SHA256.fullmatch(args.config_sha256):
        raise EvidenceError("redacted config의 SHA-256 64자리가 필요합니다")
    if not LABEL.fullmatch(args.scenario):
        raise EvidenceError("scenario는 100자 이내의 영문·숫자·점·하이픈·밑줄만 허용합니다")
    versions = (args.opensql_version, args.openproxy_version,
                args.patroni_version, args.etcd_version)
    if any(not value.strip() or len(value) > 128 or "\n" in value for value in versions):
        raise EvidenceError("제품 버전은 비어 있지 않은 한 줄의 128자 이하여야 합니다")
    git_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
                             capture_output=True, text=True).stdout.strip()
    directory = args.run_dir
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    manifest = {
        "schema_version": 1, "run_id": str(uuid.uuid4()),
        "scenario": args.scenario, "started_at": utc_now(), "git_sha": git_sha,
        "config_sha256": args.config_sha256.lower(),
        "versions": {"opensql": args.opensql_version, "openproxy": args.openproxy_version,
                     "patroni": args.patroni_version, "etcd": args.etcd_version},
    }
    write_private(directory / MANIFEST, json_text(manifest), exclusive=True)
    write_private(directory / EVENTS, "", exclusive=True)
    print(manifest["run_id"])


def export(directory):
    """Rebuild derived files from the immutable manifest and raw events."""
    manifest = read_manifest(directory)
    with locked_events(directory) as output:
        summary, rows = replay(manifest, read_events(output))
        replace_private(directory / SUMMARY, json_text(summary))
        replace_private(directory / REQUESTS, csv_text(rows))
    return summary


def verify(directory):
    """Reject missing or edited derived evidence rather than trusting a report."""
    manifest = read_manifest(directory)
    with locked_events(directory, shared=True) as output:
        summary, rows = replay(manifest, read_events(output))
        if (directory / SUMMARY).read_text(encoding="utf-8") != json_text(summary):
            raise EvidenceError("summary.json이 원본 이벤트와 불일치합니다")
        if (directory / REQUESTS).read_text(encoding="utf-8") != csv_text(rows):
            raise EvidenceError("requests.csv가 원본 이벤트와 불일치합니다")
    return summary


def parser():
    """Define the small command surface used by external load and fault runners."""
    commands = argparse.ArgumentParser(description=__doc__)
    sub = commands.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init", help="새 실험과 환경 지문 생성")
    init.add_argument("--run-dir", type=Path, required=True)
    init.add_argument("--scenario", required=True)
    init.add_argument("--config-sha256", required=True, help="비밀 제거된 설정 스냅샷의 SHA-256")
    for name in ("opensql", "openproxy", "patroni", "etcd"):
        init.add_argument(f"--{name}-version", required=True)
    for name in ("sent", "ack", "fail", "unknown", "fault", "finish", "export", "verify"):
        command = sub.add_parser(name)
        command.add_argument("--run-dir", type=Path, required=True)
        if name == "sent":
            command.add_argument("--operation", required=True)
            command.add_argument("--request-id", default=None)
        if name in ("ack", "fail", "unknown"):
            command.add_argument("--request-id", required=True)
        if name in ("ack", "fail"):
            command.add_argument("--http-status", type=int, required=True)
        if name == "unknown":
            command.add_argument("--reason", choices=("timeout", "connection_lost", "client_stopped", "other"), required=True)
        if name == "fault":
            command.add_argument("--name", required=True)
            command.add_argument("--phase", choices=("start", "end"), required=True)
    return commands


def main(argv=None):
    """Record raw events first; derived files are rebuilt only on explicit export."""
    args = parser().parse_args(argv)
    try:
        if args.command == "init":
            initialize(args)
        elif args.command == "sent":
            request_id = args.request_id or str(uuid.uuid4())
            append_event(args.run_dir, "sent", request_id=request_id, operation=args.operation)
            print(request_id)
        elif args.command in ("ack", "fail", "unknown"):
            kind = {"ack": "acknowledged", "fail": "failed", "unknown": "unknown"}[args.command]
            attributes = {"request_id": args.request_id}
            if kind == "unknown":
                attributes["reason"] = args.reason
            else:
                attributes["http_status"] = args.http_status
            append_event(args.run_dir, kind, **attributes)
        elif args.command == "fault":
            append_event(args.run_dir, "fault", name=args.name, phase=args.phase)
        elif args.command == "finish":
            append_event(args.run_dir, "finished")
            print(json_text(export(args.run_dir)), end="")
        elif args.command == "export":
            print(json_text(export(args.run_dir)), end="")
        elif args.command == "verify":
            print(json_text(verify(args.run_dir)), end="")
    except (EvidenceError, FileExistsError, FileNotFoundError, subprocess.CalledProcessError) as error:
        print(f"증거 기록 오류: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
