#!/usr/bin/env python3
"""Compare the external HTTP ledger with a read-only per-run DB count export."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

from ha_evidence import EvidenceError, LABEL, read_manifest, replace_private, verify


def reconcile(run_dir: Path, db_csv: Path):
    """Report counts without copying request IDs or infrastructure names into the summary."""
    manifest = read_manifest(run_dir)
    ledger_summary = verify(run_dir)
    if not ledger_summary["complete"]:
        raise EvidenceError("외부 원장이 종료·검증되지 않았습니다")
    with (run_dir / "requests.csv").open(newline="", encoding="utf-8") as source:
        client_rows = {row["request_id"]: row for row in csv.DictReader(source)}
    db_counts = {}
    with db_csv.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != ["request_id", "row_count"]:
            raise EvidenceError("DB CSV 열은 request_id,row_count여야 합니다")
        for row in reader:
            request_id = row["request_id"]
            if not LABEL.fullmatch(request_id) or request_id in db_counts or not request_id.startswith(manifest["run_id"] + "-"):
                raise EvidenceError("DB CSV에 잘못된 실행 ID 또는 중복 request_id가 있습니다")
            try:
                count = int(row["row_count"])
            except ValueError as error:
                raise EvidenceError("DB 행 수가 정수가 아닙니다") from error
            if count < 1:
                raise EvidenceError("DB 행 수는 양수여야 합니다")
            db_counts[request_id] = count
    acknowledged = [row for row in client_rows.values() if row["outcome"] == "ACKNOWLEDGED"]
    unknown = [row for row in client_rows.values() if row["outcome"] == "UNKNOWN"]
    failed = [row for row in client_rows.values() if row["outcome"] == "FAILED"]
    result = {
        "run_id": manifest["run_id"],
        "db_export_sha256": hashlib.sha256(db_csv.read_bytes()).hexdigest(),
        "client_request_count": len(client_rows), "db_request_count": len(db_counts),
        "db_row_count": sum(db_counts.values()),
        "acknowledged_count": len(acknowledged),
        "failed_count": len(failed), "unknown_count": len(unknown),
        "acknowledged_missing_count": sum(db_counts.get(row["request_id"], 0) == 0 for row in acknowledged),
        "acknowledged_duplicate_count": sum(db_counts.get(row["request_id"], 0) > 1 for row in acknowledged),
        "db_duplicate_request_count": sum(count > 1 for count in db_counts.values()),
        "unknown_persisted_count": sum(db_counts.get(row["request_id"], 0) > 0 for row in unknown),
        "unknown_not_persisted_count": sum(db_counts.get(row["request_id"], 0) == 0 for row in unknown),
        "failed_persisted_count": sum(db_counts.get(row["request_id"], 0) > 0 for row in failed),
        "orphan_db_request_count": sum(request_id not in client_rows for request_id in db_counts),
    }
    result["normal_baseline_pass"] = (
        result["acknowledged_missing_count"] == 0
        and result["acknowledged_duplicate_count"] == 0
        and result["db_duplicate_request_count"] == 0
        and result["orphan_db_request_count"] == 0
        and result["failed_count"] == 0
        and result["unknown_count"] == 0
        and result["unknown_persisted_count"] == 0
        and result["failed_persisted_count"] == 0
    )
    replace_private(run_dir / "reconciliation.json", json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--db-csv", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = reconcile(args.run_dir, args.db_csv)
    except (EvidenceError, OSError, KeyError) as error:
        print(f"대조 실패: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["normal_baseline_pass"] else 2


if __name__ == "__main__":
    sys.exit(main())
