#!/usr/bin/env python3
"""Emit only fixed HA-probe diagnostic fields from a local systemd journal stream."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone


RUN_ID = re.compile(r"ha[0-9]{3}[a-z0-9-]{0,55}\Z")
PHASE = r"(?:AUTHORIZATION|TX_BEGIN|SQL_EXECUTE|COMMIT_PENDING)"
TYPE = r"[A-Za-z][A-Za-z0-9]{0,79}"
SQLSTATE = r"(?:[A-Z0-9]{5}|none)"


def sanitize(line: str, run_id: str) -> dict | None:
    """Discard every journal field except time and the allowlisted diagnostic tuple."""
    try:
        source = json.loads(line)
        message = source["MESSAGE"]
        micros = int(source["__REALTIME_TIMESTAMP"])
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        return None
    if not isinstance(message, str):
        return None
    request = re.escape(run_id) + r"-v[0-9]+-i[0-9]+"
    prefix = rf"HA_PROBE_(EXCEPTION|RESULT|THROW) run={re.escape(run_id)} request=({request}) phase=({PHASE})"
    match = re.search(prefix + rf"(?: type=({TYPE}) sqlstate=({SQLSTATE}))?(?: status=([0-9]{{3}}))?(?!\S)", message)
    if match is None:
        return None
    event, request_id, phase, error_type, sqlstate, status = match.groups()
    if event == "EXCEPTION" and (error_type is None or status is None):
        return None
    if event == "THROW" and (error_type is None or status is not None):
        return None
    if event == "RESULT" and (error_type is not None or status is None):
        return None
    result = {
        "at": datetime.fromtimestamp(micros / 1_000_000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "event": event,
        "run_id": run_id,
        "request_id": request_id,
        "phase": phase,
    }
    if error_type is not None:
        result.update(type=error_type, sqlstate=sqlstate)
    if status is not None:
        result["status"] = int(status)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    if not RUN_ID.fullmatch(args.run_id):
        print("INVALID_RUN_ID", file=sys.stderr)
        return 2
    for line in sys.stdin:
        event = sanitize(line, args.run_id)
        if event is not None:
            print(json.dumps(event, ensure_ascii=True, sort_keys=True), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
