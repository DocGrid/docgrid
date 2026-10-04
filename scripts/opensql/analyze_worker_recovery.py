#!/usr/bin/env python3
"""Judge a synthetic multi-document Worker run from a redacted DB CSV snapshot."""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def analyze(rows: list[dict[str, str]], expected: int, require_retry: bool) -> dict:
    """Keep job completion, content counts, and retry evidence as separate checks."""
    if expected < 1:
        raise ValueError("EXPECTED_DOCUMENT_COUNT_INVALID")
    if len(rows) != expected or len({row["test_document"] for row in rows}) != expected:
        raise ValueError("DOCUMENT_COUNT_MISMATCH")
    # 1. Every current Version and Job must converge; HTTP upload alone is insufficient.
    incomplete = sum(any(row[field] != "INDEXED" for field in
                         ("document_status", "version_status", "job_status")) for row in rows)
    # 2. Active embeddings must match nonzero chunks; duplicate natural keys must stay absent.
    missing_or_mismatched = sum(int(row["chunks"]) == 0 or
                                int(row["embeddings"]) != int(row["chunks"]) for row in rows)
    duplicate_chunks = sum(int(row["duplicate_chunks"]) for row in rows)
    duplicate_embeddings = sum(int(row["duplicate_embeddings"]) for row in rows)
    retried = sum(int(row["retry_count"]) > 0 for row in rows)
    result = {
        "documents": expected,
        "indexed": expected - incomplete,
        "incomplete": incomplete,
        "missing_or_mismatched_embeddings": missing_or_mismatched,
        "duplicate_chunks": duplicate_chunks,
        "duplicate_embeddings": duplicate_embeddings,
        "retried_documents": retried,
    }
    result["passed"] = (incomplete == 0 and missing_or_mismatched == 0 and
                        duplicate_chunks == 0 and duplicate_embeddings == 0 and
                        (not require_retry or retried > 0))
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", required=True, type=Path)
    parser.add_argument("--expected", required=True, type=int)
    parser.add_argument("--require-retry", action="store_true")
    args = parser.parse_args()
    with args.csv.open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source))
    result = analyze(rows, args.expected, args.require_retry)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
