#!/usr/bin/env python3
"""Reconcile first HTTP outcomes with final DB rows for one synthetic HA run.

This offline auditor reads only sanitized test artifacts. It reports aggregate
counts and a verdict, never request IDs, credentials or infrastructure data.
"""

import argparse
import collections
import csv
import json
import re
from pathlib import Path


def read_events(path, run_id):
    sent = {}
    terminal = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        event = json.loads(line)
        request_id = event["request_id"]
        if event.get("run_id") != run_id or not re.fullmatch(rf"{run_id}-v\d+-i\d+", request_id):
            raise ValueError("event_run_or_request_id_invalid")
        kind = event["kind"]
        if kind == "sent":
            if request_id in sent:
                raise ValueError("duplicate_sent_event")
            sent[request_id] = event
        elif kind in ("acknowledged", "failed", "unknown"):
            if request_id in terminal:
                raise ValueError("duplicate_terminal_event")
            terminal[request_id] = event
        else:
            raise ValueError("event_kind_invalid")
    if sent.keys() != terminal.keys():
        raise ValueError("missing_sent_or_terminal_event")
    return terminal


def read_db(path, run_id):
    rows = {}
    with path.open(encoding="utf-8", newline="") as source:
        for request_id, count in csv.reader(source):
            if not re.fullmatch(rf"{run_id}-v\d+-i\d+", request_id) or request_id in rows:
                raise ValueError("db_request_id_invalid_or_duplicate_line")
            rows[request_id] = int(count)
            if rows[request_id] < 1:
                raise ValueError("db_row_count_invalid")
    return rows


def summarize(terminal, rows, after):
    kinds = collections.Counter(event["kind"] for event in terminal.values())
    statuses = collections.Counter(
        str(event["http_status"]) for event in terminal.values()
        if event["kind"] == "failed"
    )
    acknowledged = {request_id: event for request_id, event in terminal.items()
                    if event["kind"] == "acknowledged"}
    failed = {request_id for request_id, event in terminal.items() if event["kind"] == "failed"}
    unknown = {request_id for request_id, event in terminal.items() if event["kind"] == "unknown"}
    missing = [event["at"] for request_id, event in acknowledged.items() if request_id not in rows]
    post = {request_id: event for request_id, event in acknowledged.items() if event["at"] >= after}
    result = {
        "requests": len(terminal),
        "first_outcomes": dict(sorted(kinds.items())),
        "failed_http_statuses": dict(sorted(statuses.items())),
        "db_unique_ids": len(rows),
        "db_rows": sum(rows.values()),
        "db_duplicate_ids": sum(count > 1 for count in rows.values()),
        "acknowledged_in_db": sum(request_id in rows for request_id in acknowledged),
        "acknowledged_missing_db": len(missing),
        "failed_in_db": sum(request_id in rows for request_id in failed),
        "unknown_in_db": sum(request_id in rows for request_id in unknown),
        "missing_201_first_utc": min(missing) if missing else None,
        "missing_201_last_utc": max(missing) if missing else None,
        "post_recovery_201": len(post),
        "post_recovery_201_missing_db": sum(request_id not in rows for request_id in post),
    }
    result["verdict"] = "FAIL" if result["acknowledged_missing_db"] or result["db_duplicate_ids"] else "PASS"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--events", type=Path, required=True)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--post-recovery-utc", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9]{2,15}", args.run_id) or not re.fullmatch(
            r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z", args.post_recovery_utc):
        parser.error("invalid run ID or UTC cutoff")
    # 1. Validate the complete first-response ledger before judging writes.
    terminal = read_events(args.events, args.run_id)
    # 2. Read final row multiplicities from the promoted Primary's export.
    rows = read_db(args.db, args.run_id)
    # 3. Keep acknowledged loss separate from unknown transport outcomes.
    result = summarize(terminal, rows, args.post_recovery_utc)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
