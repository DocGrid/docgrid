#!/usr/bin/env python3
"""Derive bounded proxy failover observations from redacted request and JVM samples."""

from __future__ import annotations

import argparse
import csv
import json
import os
from datetime import datetime
from pathlib import Path

from sample_ha_app_connections import FIELDS


def instant(value: str) -> datetime:
    """Require a millisecond UTC timestamp so cross-host differences are explicit."""
    if len(value) != 24 or not value.endswith("Z"):
        raise ValueError("Expected millisecond UTC timestamp")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset().total_seconds() != 0:
        raise ValueError("Timestamp is not UTC")
    return parsed


def elapsed_ms(start: str, end: str) -> float:
    return round((instant(end) - instant(start)).total_seconds() * 1000, 3)


def read_samples(path: Path, role: str) -> list[dict[str, str]]:
    """Accept only the fixed, address-free sampler columns for one JVM."""
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != FIELDS:
            raise ValueError("Sampler schema differs from the safe allowlist")
        rows = list(reader)
    if not rows:
        raise ValueError("No connection samples")
    previous = None
    for row in rows:
        if row["role"] != role:
            raise ValueError("Mixed application roles")
        current = instant(row["at_utc"])
        if previous is not None and current <= previous:
            raise ValueError("Sampler timestamps are not increasing")
        previous = current
        for name in FIELDS[2:]:
            float(row[name])
    return rows


def connection_result(rows: list[dict[str, str]], dead: str, kill_at: str,
                      ready_at: str) -> dict:
    """Report sampled connection movement, not an invented exact socket-open instant."""
    survivor = "proxy_b" if dead == "proxy_a" else "proxy_a"
    window = [(index, row) for index, row in enumerate(rows)
              if kill_at <= row["at_utc"] <= ready_at]
    if not window or rows[0]["at_utc"] >= kill_at or rows[-1]["at_utc"] <= ready_at:
        raise ValueError("Sampler did not cover both sides of the fault")

    def first_sample(predicate):
        for index, row in window:
            if predicate(index, row):
                return index, row
        return None

    # 1. Ignore connections created during warm-up before the fault marker.
    before_kill = next((row for row in reversed(rows) if row["at_utc"] < kill_at), rows[0])
    prior_new = int(before_kill[f"{survivor}_new_cumulative"])
    new_socket = first_sample(
        lambda _, row: int(row[f"{survivor}_new_cumulative"]) > prior_new
    )

    # 2. Three consecutive 500 ms samples distinguish a momentary count from pool stability.
    def stable_at(index, row):
        if index + 2 >= len(rows) or rows[index + 2]["at_utc"] > ready_at:
            return False
        return all(
            int(sample[f"{dead}_established"]) == 0
            and int(sample[f"{survivor}_established"]) == 5
            and float(sample["hikari_pending"]) == 0
            and int(sample["metrics_ok"]) == 1
            for sample in rows[index:index + 3]
        )

    stable = first_sample(stable_at)

    def boundary(found):
        if found is None:
            return None
        index, row = found
        previous = rows[index - 1]["at_utc"] if index > 0 else kill_at
        return {
            "first_observed_at": row["at_utc"],
            "since_kill_upper_bound_ms": elapsed_ms(kill_at, row["at_utc"]),
            "since_kill_lower_bound_ms": max(0, elapsed_ms(kill_at, previous)),
        }

    stability = boundary(stable)
    if stable is not None:
        # Confirmation is only known at the third sample, not at the first sample.
        confirmed_at = rows[stable[0] + 2]["at_utc"]
        stability["confirmed_at"] = confirmed_at
        stability["confirmed_since_kill_ms"] = elapsed_ms(kill_at, confirmed_at)

    # 3. A ready proxy need not receive connections again while Hikari keeps five survivors.
    ready_index = next((i for i, row in enumerate(rows) if row["at_utc"] >= ready_at), None)
    recovered_new = None
    if ready_index is not None:
        before = int(rows[ready_index - 1][f"{dead}_new_cumulative"]) if ready_index else 0
        recovered_new = next((row["at_utc"] for row in rows[ready_index:]
                              if int(row[f"{dead}_new_cumulative"]) > before), None)
    return {
        "sample_count": len(rows),
        "missing_metrics_count": sum(row["metrics_ok"] != "1" for row in rows),
        "baseline_established": {name: int(rows[0][f"{name}_established"])
                                 for name in ("proxy_a", "proxy_b")},
        "first_new_survivor_connection": boundary(new_socket),
        "survivor_pool_stable_three_samples": stability,
        "max_hikari_pending": max(float(row["hikari_pending"]) for row in rows),
        "end_established": {name: int(rows[-1][f"{name}_established"])
                            for name in ("proxy_a", "proxy_b")},
        "end_new_connection_cumulative": {
            name: int(rows[-1][f"{name}_new_cumulative"])
            for name in ("proxy_a", "proxy_b")
        },
        "first_new_recovered_proxy_connection_after_ready": recovered_new,
    }


def analyze(run_dir: Path, fault_target: str, kill_at: str, ready_at: str) -> dict:
    """Join client outcomes with the per-JVM samples without exposing request IDs."""
    dead = f"proxy_{fault_target}"
    if fault_target not in ("a", "b") or instant(ready_at) <= instant(kill_at):
        raise ValueError("Invalid fault target or time order")
    with (run_dir / "requests.csv").open(newline="", encoding="utf-8") as source:
        requests = list(csv.DictReader(source))
    if not requests:
        raise ValueError("No request ledger")
    failures = sorted((row["completed_at"] for row in requests if row["outcome"] == "FAILED"))
    last_failure = failures[-1] if failures else None
    # 4. Exclude successes already in flight before the last failed response.
    post_failure_success = min((row["completed_at"] for row in requests
                                if last_failure is not None and row["outcome"] == "ACKNOWLEDGED"
                                and row["sent_at"] > last_failure), default=None)
    response = {
        "failed_count": len(failures),
        "first_failed_at": failures[0] if failures else None,
        "last_failed_at": last_failure,
        "first_newly_started_success_after_last_failure_at": post_failure_success,
        "last_failure_to_new_success_ms": elapsed_ms(last_failure, post_failure_success)
        if post_failure_success else None,
        "kill_to_new_success_ms": elapsed_ms(kill_at, post_failure_success)
        if post_failure_success else None,
    }
    return {
        "run_id": run_dir.name,
        "fault_target": dead,
        "kill_command_returned_at_utc": kill_at,
        "proxy_ready_observed_at_utc": ready_at,
        "fault_marker_to_ready_ms": elapsed_ms(kill_at, ready_at),
        "http": response,
        "apps": {role: connection_result(
            read_samples(run_dir / f"{role}-connections.csv", role), dead, kill_at, ready_at)
            for role in ("app-a", "app-b")},
        "measurement_limit": "TCP 연결·풀 수렴은 500ms 표본 구간이며 KILL 마커는 명령 반환 시각이지 물리적 종료 순간이 아닙니다.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--fault-target", choices=("a", "b"), required=True)
    parser.add_argument("--kill-at", required=True)
    parser.add_argument("--ready-at", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = analyze(args.run_dir, args.fault_target, args.kill_at, args.ready_at)
        descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(result, target, ensure_ascii=False, sort_keys=True, indent=2)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
    except (OSError, ValueError, KeyError) as error:
        print(f"복구 계측 분석 실패: {error}")
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
