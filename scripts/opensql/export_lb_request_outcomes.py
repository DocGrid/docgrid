#!/usr/bin/env python3
"""Export allowlisted LB request outcomes without persisting infrastructure identifiers."""

from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import os
import re
import subprocess
from collections import Counter
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,59}\Z")
FIELDS = ("at_utc", "request_id", "http_status", "origin", "latency_ms")


def classify(entry: dict, run_id: str) -> dict | None:
    """Retain synthetic IDs and numeric outcomes; discard every source address and label."""
    request = entry.get("httpRequest", {})
    query = parse_qs(urlsplit(request.get("requestUrl", "")).query)
    ids = query.get("ha_request_id", [])
    if len(ids) != 1 or not ids[0].startswith(run_id + "-") or not RUN_ID.fullmatch(ids[0]):
        return None
    status = request.get("status")
    proxy = entry.get("jsonPayload", {}).get("proxyStatus", "")
    # A disconnected client has no HTTP response code in Cloud Logging.
    if status is None and 'details="client_disconnected_before_any_response"' in proxy:
        status = ""
    elif not isinstance(status, int) or not 100 <= status <= 599:
        raise ValueError("LB status is missing or invalid")
    if 'details="failed_to_pick_backend"' in proxy:
        origin = "load_balancer_no_backend"
    elif 'details="response_sent_by_backend"' in proxy:
        origin = "backend_response"
    elif 'details="client_disconnected_before_any_response"' in proxy:
        origin = "client_disconnected"
    else:
        origin = "undetermined"
    latency = request.get("latency", "")
    if not re.fullmatch(r"\d+(?:\.\d+)?s", latency):
        raise ValueError("LB latency is missing or invalid")
    at = entry.get("timestamp", "")
    if not re.fullmatch(r"\d{4}-\d\d-\d\dT[\d:.]+Z", at):
        raise ValueError("LB timestamp is missing or invalid")
    return {"at_utc": at, "request_id": ids[0], "http_status": status,
            "origin": origin, "latency_ms": round(float(latency[:-1]) * 1000, 3)}


def export(raw: str, run_id: str) -> tuple[list[dict], dict]:
    """Reject duplicate request logs and summarize their source without raw payloads."""
    entries = json.loads(raw)
    if not isinstance(entries, list):
        raise ValueError("LB response is not a list")
    rows = [row for entry in entries if (row := classify(entry, run_id)) is not None]
    if len({row["request_id"] for row in rows}) != len(rows):
        raise ValueError("Duplicate LB request IDs")
    rows.sort(key=lambda row: (row["at_utc"], row["request_id"]))
    counts = Counter((row["http_status"], row["origin"]) for row in rows)
    summary = {f"{status if status != '' else 'no_http_status'}:{origin}": count
               for (status, origin), count in sorted(counts.items(), key=lambda item: str(item[0]))}
    return rows, summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=10000)
    args = parser.parse_args()
    if not RUN_ID.fullmatch(args.run_id) or not 1 <= args.limit <= 20000 or args.output.exists():
        parser.error("Invalid run ID, limit, or reused output")
    query = ('resource.type="internal_http_lb_rule" AND '
             f'httpRequest.requestUrl:"{args.run_id}"')
    # 1. Keep the full Cloud Logging response in memory; never persist URLs or addresses.
    result = subprocess.run(
        ["gcloud", "logging", "read", query, "--freshness=2h",
         f"--limit={args.limit}", "--format=json"],
        capture_output=True, text=True, check=True, timeout=180,
    )
    rows, summary = export(result.stdout, args.run_id)
    # 2. Persist only allowlisted values with owner-only permissions.
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as destination:
        with gzip.GzipFile(filename="", mode="wb", fileobj=destination, mtime=0) as compressed:
            text = io.StringIO()
            writer = csv.DictWriter(text, fieldnames=FIELDS, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
            compressed.write(text.getvalue().encode())
        destination.flush()
        os.fsync(destination.fileno())
    print(json.dumps({"run_id": args.run_id, "logged_requests": len(rows),
                      "outcomes": summary}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
