#!/usr/bin/env python3
"""Refuse a VM fault unless its independent delayed Workflows guard is armed."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path


def gate(execution: dict, expected_workflow: str, now: datetime, min_remaining: int = 120) -> dict:
    """Return a safe, identifier-free readiness result for one execution snapshot."""
    # 1. Check the exact workflow and active execution before trusting its timer.
    name = execution.get("name", "")
    if not name.startswith(expected_workflow + "/executions/") or execution.get("state") != "ACTIVE":
        raise ValueError("GUARD_NOT_ACTIVE_OR_WRONG_WORKFLOW")

    # 2. A stale or malformed execution must never authorize a new fault.
    try:
        argument = json.loads(execution["argument"])
        delay = argument["delay_seconds"]
        started = datetime.fromisoformat(execution["startTime"].replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError) as error:
        raise ValueError("GUARD_EXECUTION_METADATA_INVALID") from error
    if not isinstance(delay, int) or isinstance(delay, bool) or not 120 <= delay <= 3600:
        raise ValueError("GUARD_DELAY_INVALID")
    if started.tzinfo is None or now.tzinfo is None:
        raise ValueError("GUARD_TIMEZONE_MISSING")
    remaining = (started + timedelta(seconds=delay) - now).total_seconds()
    if remaining < min_remaining:
        raise ValueError("GUARD_DEADLINE_TOO_CLOSE")

    # 3. Only this validated snapshot permits the operator to continue preflight.
    return {"guard": "ARMED", "remaining_seconds": int(remaining)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execution-json", required=True, type=Path)
    parser.add_argument("--workflow-resource", required=True)
    parser.add_argument("--min-remaining-seconds", type=int, default=120)
    args = parser.parse_args()
    with args.execution_json.open(encoding="utf-8") as source:
        execution = json.load(source)
    try:
        result = gate(execution, args.workflow_resource, datetime.now(timezone.utc),
                      args.min_remaining_seconds)
    except ValueError as error:
        print(json.dumps({"guard": "BLOCKED", "reason": str(error)}, ensure_ascii=False))
        raise SystemExit(2) from None
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
