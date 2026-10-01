#!/usr/bin/env python3
"""Sample bounded-cardinality app metrics during a WebSocket benchmark."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time
from urllib.request import urlopen

PREFIXES = (
    "hikaricp_connections",
    "executor_",
    "process_cpu_usage",
    "system_cpu_usage",
    "jvm_memory_used_bytes",
    "jvm_threads_live_threads",
    "docgrid_stomp_sessions_",
)


def scrape(url: str) -> dict[str, float]:
    with urlopen(url, timeout=3) as response:
        payload = response.read().decode("utf-8")
    metrics = {}
    for line in payload.splitlines():
        if not line or line.startswith("#"):
            continue
        name, _, value = line.partition(" ")
        bare_name = name.split("{", 1)[0]
        if bare_name.startswith(PREFIXES):
            try:
                metrics[name] = float(value.strip())
            except ValueError:
                continue
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8081/actuator/prometheus")
    parser.add_argument("--interval-seconds", type=float, default=2.0)
    parser.add_argument("--duration-seconds", type=float, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.interval_seconds <= 0 or args.duration_seconds <= 0:
        parser.error("interval and duration must be positive")
    deadline = time.monotonic() + args.duration_seconds
    with Path(args.output).open("w", encoding="utf-8") as output:
        while time.monotonic() < deadline:
            record = {"at": datetime.now(timezone.utc).isoformat()}
            try:
                record["metrics"] = scrape(args.url)
            except Exception as error:
                # A scrape exception can contain the private metrics URL.
                record["error_type"] = type(error).__name__
            output.write(json.dumps(record, sort_keys=True) + "\n")
            output.flush()
            time.sleep(args.interval_seconds)


if __name__ == "__main__":
    main()
