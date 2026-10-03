#!/usr/bin/env python3
"""Arm three independent VM cleanup timers before creating one HA probe fixture.

This operator-only runner never injects a database fault or prints fixture IDs,
credentials, or connection details. A surviving timer may clean the fixture after
one DB VM is lost; cleanup remains incomplete until every reachable timer is
audited and any temporarily unavailable VM is checked after it returns.
"""

from __future__ import annotations

import argparse
import re
import sys
import time

from permission_replica_lag import NODES, InvalidExperiment, check_target, remote


# Existing stale-count guard covers the 12-character IDs used by permission runs.
RUN_ID = re.compile(r"[a-z0-9]{12}\Z")
HELPER = "docgrid-permission-fixture"


def call(node: str, action: str, run_id: str, guard_seconds: int) -> str:
    """Invoke only the installed allowlisted fixture helper on its own VM."""
    return remote(node, HELPER, action, node, run_id, str(guard_seconds))


def roles(run_id: str, guard_seconds: int, *, require_all: bool) -> tuple[dict[str, str], list[str]]:
    """Find the observed primary without assuming that node1 survived."""
    observed: dict[str, str] = {}
    unavailable: list[str] = []
    for node in NODES:
        try:
            role = call(node, "role", run_id, guard_seconds)
        except InvalidExperiment:
            unavailable.append(node)
            continue
        if role not in {"primary", "replica"}:
            raise InvalidExperiment("Unexpected local database role")
        observed[node] = role
    minimum = len(NODES) if require_all else 2
    if len(observed) < minimum or list(observed.values()).count("primary") != 1:
        raise InvalidExperiment("Cannot establish one primary and enough reachable VM guards")
    return observed, unavailable


def guard(node: str, run_id: str, guard_seconds: int) -> tuple[bool, int]:
    """Parse the helper's safe, numeric-only timer and fixture status."""
    value = call(node, "guard-status", run_id, guard_seconds)
    match = re.fullmatch(r"armed=(true|false) fixture_count=([0-9]+)", value)
    if match is None:
        raise InvalidExperiment("Malformed fixture guard status")
    return match.group(1) == "true", int(match.group(2))


def prepare(run_id: str, guard_seconds: int) -> None:
    """Create users only after all three independent timers are verified pending."""
    observed, _ = roles(run_id, guard_seconds, require_all=True)
    primary = next(node for node, role in observed.items() if role == "primary")
    if call(primary, "stale-count", run_id, guard_seconds) != "0":
        raise InvalidExperiment("Older synthetic ADMIN users need manual inspection")
    armed: list[str] = []
    fixture_may_exist = False
    try:
        # 1. A new run must not reuse any pending timer or existing users.
        for node in NODES:
            if guard(node, run_id, guard_seconds) != (False, 0):
                raise InvalidExperiment("A fixture or timer already exists for this run")
        # 2. Timers on all candidates outlive this process and a single VM outage.
        for node in NODES:
            armed.append(node)
            call(node, "arm", run_id, guard_seconds)
        if any(guard(node, run_id, guard_seconds) != (True, 0) for node in NODES):
            raise InvalidExperiment("Not all three independent timers are pending")
        if roles(run_id, guard_seconds, require_all=True)[0] != observed:
            raise InvalidExperiment("Database roles changed while arming cleanup")
        # 3. Once INSERT is attempted, keep all timers: its result may be unknown.
        fixture_may_exist = True
        created = call(primary, "create", run_id, guard_seconds).splitlines()
        aliases = {row.split(",")[0] for row in created}
        expected = {f"ha-{run_id}-{suffix}" for suffix in ("admin", "m", "c1", "c2")}
        if len(created) != 4 or aliases != expected or any(
                len(row.split(",")) != 2 or not row.split(",")[1].isdigit() for row in created):
            raise InvalidExperiment("Fixture creation returned an incomplete user set")
        print(f"실행 ID={run_id} 시험 계정=4명 독립 정리 타이머=3/3 무장")
    except Exception:
        if not fixture_may_exist:
            for node in armed:
                try:
                    call(node, "cancel", run_id, guard_seconds)
                except InvalidExperiment:
                    pass  # A failed rollback is visible to the next preflight.
        raise


def cleanup(run_id: str, guard_seconds: int) -> None:
    """Delete on the observed primary; never cancel a timer before zero users."""
    observed, unavailable = roles(run_id, guard_seconds, require_all=False)
    primary = next(node for node, role in observed.items() if role == "primary")
    # 1. An unavailable primary stops cleanup; independent VM timers keep retrying.
    call(primary, "remove", run_id, guard_seconds)
    if guard(primary, run_id, guard_seconds)[1] != 0:
        raise InvalidExperiment("Primary still has run-scoped fixture users")
    # 2. Wait for each reachable replica to replay the deletion before disarming it.
    for attempt in range(30):
        if all(guard(node, run_id, guard_seconds)[1] == 0 for node in observed):
            break
        if attempt == 29:
            raise InvalidExperiment("Replica has not replayed fixture deletion")
        time.sleep(1)
    # 3. A down VM is not silently declared clean; its timer must be audited later.
    for node in observed:
        call(node, "cancel", run_id, guard_seconds)
        if guard(node, run_id, guard_seconds) != (False, 0):
            raise InvalidExperiment("Fixture guard cancellation was not verified")
    if unavailable:
        raise InvalidExperiment("Users removed; unavailable VM guard still needs audit after recovery")
    print(f"실행 ID={run_id} 시험 계정 잔여=0명 정리 타이머=0/3")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "cleanup", "status"))
    parser.add_argument("run_id")
    parser.add_argument("--guard-seconds", type=int, default=900)
    args = parser.parse_args()
    if RUN_ID.fullmatch(args.run_id) is None or not 300 <= args.guard_seconds <= 1800:
        parser.error("run_id 또는 정리 타이머 범위가 잘못되었습니다")
    check_target()
    if args.action == "prepare":
        prepare(args.run_id, args.guard_seconds)
    elif args.action == "cleanup":
        cleanup(args.run_id, args.guard_seconds)
    else:
        observed, unavailable = roles(args.run_id, args.guard_seconds, require_all=False)
        for node in observed:
            armed, count = guard(node, args.run_id, args.guard_seconds)
            print(f"노드={NODES.index(node) + 1} 역할={observed[node]} 타이머={armed} 시험 계정={count}명")
        print(f"접근 불가 노드={len(unavailable)}개")


if __name__ == "__main__":
    try:
        main()
    except InvalidExperiment as error:
        print(f"안전 관문 실패: {error}", file=sys.stderr)
        sys.exit(1)
