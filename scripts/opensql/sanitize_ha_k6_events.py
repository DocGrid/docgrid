#!/usr/bin/env python3
"""Copy only validated HA probe event fields before evidence leaves the load VM."""

from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
from datetime import datetime
from pathlib import Path


RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,59}\Z")
AT = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z\Z")
EXPECTED = {
    "sent": {"at", "event_id", "kind", "operation", "request_id", "run_id"},
    "acknowledged": {"at", "event_id", "http_status", "kind", "request_id", "run_id"},
    "failed": {"at", "event_id", "http_status", "kind", "request_id", "run_id"},
    "unknown": {"at", "event_id", "kind", "reason", "request_id", "run_id"},
}


def safe_event(value: object, run_id: str) -> dict:
    """Reject unexpected keys and values instead of copying untrusted console output."""
    if not isinstance(value, dict) or value.get("kind") not in EXPECTED:
        raise ValueError("unexpected event kind")
    kind = value["kind"]
    if set(value) != EXPECTED[kind] or value["run_id"] != run_id:
        raise ValueError("unexpected event fields")
    request_id = value["request_id"]
    if not isinstance(request_id, str) or not re.fullmatch(
        re.escape(run_id) + r"-v[0-9]+-i[0-9]+", request_id
    ) or value["event_id"] != f"{request_id}-{kind}":
        raise ValueError("unexpected request identifier")
    at = value["at"]
    if not isinstance(at, str) or not AT.fullmatch(at):
        raise ValueError("unexpected event time")
    datetime.fromisoformat(at.replace("Z", "+00:00"))
    if kind == "sent" and value["operation"] != "ha_probe_write":
        raise ValueError("unexpected operation")
    if kind == "acknowledged" and type(value["http_status"]) is not int or \
       kind == "acknowledged" and value["http_status"] != 201:
        raise ValueError("unexpected success status")
    if kind == "failed" and (type(value["http_status"]) is not int or
                             not 300 <= value["http_status"] < 600):
        raise ValueError("unexpected failure status")
    if kind == "unknown" and value["reason"] not in {"timeout", "connection_lost", "client_stopped", "other"}:
        raise ValueError("unexpected unknown reason")
    return value


def sanitize(source: Path, destination: Path, run_id: str) -> int:
    """Write a private replacement only when the entire source passes validation."""
    if not RUN_ID.fullmatch(run_id) or destination.exists():
        raise ValueError("invalid run or existing output")
    descriptor, temporary = tempfile.mkstemp(prefix=".safe-k6-", dir=destination.parent)
    count = 0
    try:
        with source.open(encoding="utf-8") as events, os.fdopen(descriptor, "w", encoding="utf-8") as output:
            for line in events:
                event = safe_event(json.loads(line), run_id)
                output.write(json.dumps(event, ensure_ascii=True, sort_keys=True) + "\n")
                count += 1
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    try:
        count = sanitize(args.source, args.output, args.run_id)
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        print("SAFE_EVENT_REJECTED")
        return 2
    print(f"safe_events={count}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
