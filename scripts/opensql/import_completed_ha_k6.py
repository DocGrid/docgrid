#!/usr/bin/env python3
"""Archive one completed, allowlisted k6 run with its original event timestamps.

This offline importer does not issue HTTP requests or alter a database. It reuses
the HA ledger validator and reconciler rather than inventing a second outcome
definition for runs whose k6 stream was recorded before local import.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import uuid
from datetime import datetime
from pathlib import Path

from ha_evidence import (EVENTS, MANIFEST, EvidenceError, export, import_k6,
                         json_text, locked_events, read_events, replay, write_private)
from reconcile_ha_probe import reconcile


def observed_bounds(path: Path, run_id: str) -> tuple[str, str]:
    """Use the first and last *observed* k6 instants, never archive wall time."""
    instants: list[tuple[datetime, str]] = []
    with path.open(encoding="utf-8") as source:
        for line in source:
            event = json.loads(line)
            if (not isinstance(event, dict) or event.get("run_id") != run_id
                    or not isinstance(event.get("at"), str)):
                raise EvidenceError("k6 원장의 실행 ID 또는 시각이 올바르지 않습니다")
            at = event["at"]
            parsed = datetime.fromisoformat(at.replace("Z", "+00:00"))
            if parsed.tzinfo is None:
                raise EvidenceError("k6 시각에 시간대가 없습니다")
            instants.append((parsed, at))
    if not instants:
        raise EvidenceError("비어 있는 k6 원장은 가져올 수 없습니다")
    return min(instants)[1], max(instants)[1]


def archive(args: argparse.Namespace) -> dict:
    """Validate, replay, and reconcile a completed run without changing evidence."""
    started_at, finished_at = observed_bounds(args.events, args.run_id)
    config_sha256 = hashlib.sha256(args.config_file.read_bytes()).hexdigest()
    git_sha = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=Path(__file__).resolve().parents[2],
        capture_output=True, text=True, check=True,
    ).stdout.strip()

    # 1. Create a private manifest whose times describe the original run.
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    manifest = {
        "schema_version": 1, "run_id": args.run_id,
        "scenario": args.scenario, "started_at": started_at,
        "git_sha": git_sha, "config_sha256": config_sha256,
        "versions": {
            "opensql": args.opensql_version, "openproxy": args.openproxy_version,
            "patroni": args.patroni_version, "etcd": args.etcd_version,
        },
    }
    write_private(args.output / MANIFEST, json_text(manifest), exclusive=True)
    write_private(args.output / EVENTS, "", exclusive=True)

    # 2. The existing importer rejects leaked fields and invalid request events.
    import_k6(args.output, args.events)
    finished = {
        "event_id": str(uuid.uuid4()), "run_id": args.run_id,
        "at": finished_at, "kind": "finished",
    }
    with locked_events(args.output) as destination:
        prior = read_events(destination)
        replay(manifest, prior + [finished])
        destination.seek(0, 2)
        destination.write(json.dumps(finished, ensure_ascii=False, sort_keys=True) + "\n")
        destination.flush()

    # 3. Rebuild requests from the original stream and compare every ID to DB rows.
    export(args.output)
    write_private(args.output / "k6-summary.json",
                  args.k6_summary.read_text(encoding="utf-8"), exclusive=True)
    write_private(args.output / "db-counts.csv",
                  args.db_csv.read_text(encoding="utf-8"), exclusive=True)
    return reconcile(args.output, args.output / "db-counts.csv")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("events", "k6-summary", "db-csv", "config-file", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    for name in ("run-id", "scenario", "opensql-version", "openproxy-version",
                 "patroni-version", "etcd-version"):
        parser.add_argument(f"--{name}", required=True)
    args = parser.parse_args()
    try:
        result = archive(args)
    except (OSError, ValueError, EvidenceError, subprocess.CalledProcessError) as error:
        print(f"완료된 k6 원장 가져오기 실패: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
