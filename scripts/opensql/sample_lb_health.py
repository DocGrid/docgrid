#!/usr/bin/env python3
"""Record bounded, numeric LB backend health without persisting infrastructure IDs."""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


SAFE_NAME = re.compile(r"[a-z][a-z0-9-]{0,62}\Z")
FIELDS = ("at_utc", "run_id", "healthy", "unhealthy", "other", "query_ms", "sample_ok")


def health_counts(raw: str) -> tuple[int, int, int]:
    """Count only backend states; discard addresses, projects, names and URLs."""
    response = json.loads(raw)
    if not isinstance(response, list):
        raise ValueError("LB health response is not a list")
    states = []
    for backend in response:
        status = backend.get("status", {})
        for item in status.get("healthStatus", []):
            states.append(item.get("healthState"))
    if not states:
        raise ValueError("LB returned no backend health states")
    return (states.count("HEALTHY"), states.count("UNHEALTHY"),
            len(states) - states.count("HEALTHY") - states.count("UNHEALTHY"))


def run(args: argparse.Namespace) -> int:
    """Sample through gcloud and flush each row while the fault is active."""
    if not SAFE_NAME.fullmatch(args.service) or not SAFE_NAME.fullmatch(args.region) or \
       not SAFE_NAME.fullmatch(args.run_id) or not 10 <= args.duration_seconds <= 300 or \
       args.interval_seconds not in (2, 3, 4, 5) or args.output.exists():
        raise ValueError("Invalid or reused LB health sample")
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        started = time.monotonic()
        count = 0
        failures = 0
        while time.monotonic() - started < args.duration_seconds:
            # 1. Query the configured backend service, retaining raw JSON only in memory.
            queried = time.monotonic()
            result = subprocess.run(
                ["gcloud", "compute", "backend-services", "get-health", args.service,
                 f"--region={args.region}", "--format=json"],
                capture_output=True, text=True, timeout=15, check=False,
            )
            try:
                if result.returncode != 0:
                    raise ValueError("LB health query failed")
                healthy, unhealthy, other = health_counts(result.stdout)
                ok = 1
            except (ValueError, TypeError, KeyError):
                healthy = unhealthy = other = -1
                ok = 0
                failures += 1
            # 2. Store only UTC time and numbers, never raw CLI output or stderr.
            writer.writerow({
                "at_utc": datetime.now(timezone.utc).isoformat(timespec="milliseconds")
                    .replace("+00:00", "Z"),
                "run_id": args.run_id, "healthy": healthy, "unhealthy": unhealthy,
                "other": other, "query_ms": round((time.monotonic() - queried) * 1000),
                "sample_ok": ok,
            })
            output.flush()
            os.fsync(output.fileno())
            count += 1
            time.sleep(max(0, args.interval_seconds - (time.monotonic() - queried)))
    print(f"samples={count} failed_queries={failures}")
    return 0 if failures == 0 else 2


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--interval-seconds", type=int, default=4)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        return run(args)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        print("LB_HEALTH_SAMPLER_FAILED")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
