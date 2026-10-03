#!/usr/bin/env python3
"""Summarize one primary-fault run without exposing request or infrastructure IDs."""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path


def instant(value: str) -> datetime:
    """Parse an ISO UTC timestamp from the allowlisted external ledger."""
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def analyze(run_dir: Path) -> dict:
    """Separate leader observation from client-visible recovery using sent times."""
    with (run_dir / "requests.csv").open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source))
    with (run_dir / "events.jsonl").open(encoding="utf-8") as source:
        events = [json.loads(line) for line in source]
    fault_starts = [instant(event["at"]) for event in events
                    if event.get("kind") == "fault" and event.get("phase") == "start"]
    if len(fault_starts) != 1:
        raise ValueError("one fault start is required")
    fault_at = fault_starts[0]

    # 1. Treat failed HTTP responses and ambiguous timeouts as separate bad starts.
    sent = [(instant(row["sent_at"]), row["outcome"]) for row in rows]
    bad = [at for at, outcome in sent if outcome != "ACKNOWLEDGED"]
    good = [at for at, outcome in sent if outcome == "ACKNOWLEDGED"]
    if not bad or not good:
        raise ValueError("fault analysis needs both acknowledged and non-acknowledged requests")
    first_bad, last_bad = min(bad), max(bad)
    before = [at for at in good if at < fault_at]
    after = [at for at in good if at > last_bad]
    if not before or not after:
        raise ValueError("successful requests are required before and after the fault")

    # 2. Five full UTC-second buckets without any bad starts define stable recovery.
    buckets: dict[datetime, dict[str, int]] = defaultdict(lambda: {"ok": 0, "bad": 0})
    for at, outcome in sent:
        bucket = at.replace(microsecond=0)
        buckets[bucket]["ok" if outcome == "ACKNOWLEDGED" else "bad"] += 1
    stable_at = None
    for second in sorted(buckets):
        if second <= last_bad:
            continue
        window = [buckets.get(second + timedelta(seconds=offset)) for offset in range(5)]
        if all(item and item["ok"] > 0 and item["bad"] == 0 for item in window):
            stable_at = second
            break
    if stable_at is None:
        raise ValueError("five consecutive successful seconds were not observed")
    return {
        "run_id": run_dir.name,
        "fault_start_utc": fault_at.isoformat(),
        "first_non_ack_sent_utc": first_bad.isoformat(),
        "last_non_ack_sent_utc": last_bad.isoformat(),
        "last_ack_before_fault_utc": max(before).isoformat(),
        "first_ack_after_last_non_ack_utc": min(after).isoformat(),
        "stable_recovery_bucket_utc": stable_at.isoformat(),
        "fault_to_stable_recovery_seconds": round((stable_at - fault_at).total_seconds(), 3),
        "observed_write_gap_seconds": round((stable_at - max(before)).total_seconds(), 3),
        "non_ack_count": len(bad),
        "seconds_with_non_ack": sum(value["bad"] > 0 for value in buckets.values()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(analyze(args.run_dir), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
