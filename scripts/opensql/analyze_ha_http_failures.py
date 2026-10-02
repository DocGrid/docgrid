#!/usr/bin/env python3
"""Summarize HTTP fault timing from the redacted HA ledger without emitting request IDs."""

from __future__ import annotations

import argparse
import csv
import gzip
import json
from datetime import datetime
from pathlib import Path


FIELDS = ("request_id", "operation", "sent_at", "completed_at", "outcome", "http_status", "reason")


def instant(value: str) -> datetime:
    """Accept only UTC timestamps used by the HA evidence ledger."""
    if not value.endswith("Z"):
        raise ValueError("UTC timestamp required")
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def milliseconds(start: datetime, end: datetime) -> float:
    return round((end - start).total_seconds() * 1000, 3)


def analyze(run_dir: Path) -> dict:
    """Join existing safe summaries; never copy IDs, reasons or exception messages to output."""
    recovery = json.loads((run_dir / "connection-recovery.json").read_text(encoding="utf-8"))
    reconciliation = json.loads((run_dir / "reconciliation.json").read_text(encoding="utf-8"))
    kill_at = instant(recovery["kill_command_returned_at_utc"])
    failures = []
    with gzip.open(run_dir / "requests.csv.gz", "rt", encoding="utf-8", newline="") as source:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != FIELDS:
            raise ValueError("Request ledger schema differs from the safe allowlist")
        for row in reader:
            if row["outcome"] == "FAILED" and row["http_status"] == "500":
                sent_at = instant(row["sent_at"])
                completed_at = instant(row["completed_at"])
                if completed_at < sent_at:
                    raise ValueError("Response precedes request")
                failures.append((sent_at, completed_at))

    if len(failures) != recovery["http"]["failed_count"] or len(failures) != reconciliation["failed_count"]:
        raise ValueError("Failed-request counts disagree across evidence files")
    if not failures:
        return {"http_500_count": 0, "sent_before_kill_marker": 0,
                "sent_at_or_after_kill_marker": 0, "failed_persisted_count": reconciliation["failed_persisted_count"]}

    sent_offsets = [milliseconds(kill_at, sent) for sent, _ in failures]
    completed_offsets = [milliseconds(kill_at, done) for _, done in failures]
    durations = [milliseconds(sent, done) for sent, done in failures]
    return {
        "http_500_count": len(failures),
        "sent_before_kill_marker": sum(offset < 0 for offset in sent_offsets),
        "sent_at_or_after_kill_marker": sum(offset >= 0 for offset in sent_offsets),
        "sent_offset_ms_min_max": [min(sent_offsets), max(sent_offsets)],
        "completed_offset_ms_min_max": [min(completed_offsets), max(completed_offsets)],
        "duration_ms_min_max": [min(durations), max(durations)],
        "failed_persisted_count": reconciliation["failed_persisted_count"],
        "limit": "KILL command-return is an observation marker, not the physical process-exit instant; HTTP data cannot identify the failing SQL stage.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = analyze(args.run_dir)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        # 1. Do not print exception text: file paths and malformed input can contain private identifiers.
        print(f"분석 실패: {type(error).__name__}")
        return 2
    # 2. Output only aggregated numbers and a fixed limitation; raw request IDs never leave the input.
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
