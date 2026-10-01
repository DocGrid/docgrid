#!/usr/bin/env python3
"""Capture only allowlisted dashboard-role SQL counters from a GCP OpenSQL primary."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import re
import shlex
import subprocess


KST = timezone(timedelta(hours=9))
SQL = ("SELECT row_to_json(s) FROM (SELECT d.datname, p.queryid, p.calls, "
       "p.total_exec_time, p.rows, p.query FROM pg_stat_statements p "
       "JOIN pg_database d ON d.oid = p.dbid) s")


def classify(query: str) -> str | None:
    """Identify only the three authorization statements, never persist SQL text."""
    normalized = re.sub(r"\s+", " ", query.strip().lower())
    if normalized.rstrip(";") == "select pg_is_in_recovery()":
        return "primary_확인"
    if "from user_roles" in normalized and "join roles" in normalized:
        if normalized.startswith("select distinct ") and " in " in normalized:
            return "admin_일괄_조회"
        if re.match(r"^select \w+\.code from user_roles ", normalized) and "user_id=$1" in normalized:
            return "사용자별_역할_조회"
    return None


def safe_rows(raw: str, database: str) -> list[dict]:
    """Discard database names and query text in memory before any artifact write."""
    totals: dict[str, dict] = {}
    for line in raw.splitlines():
        try:
            value = json.loads(line)
            if value.get("datname") != database:
                continue
            kind = classify(value.get("query", ""))
            if kind is None:
                continue
            total = totals.setdefault(kind, {"종류": kind, "누적_호출": 0,
                                             "누적_실행_ms": 0.0,
                                             "누적_반환행": 0, "통계_행수": 0})
            total["누적_호출"] += int(value["calls"])
            total["누적_실행_ms"] += float(value["total_exec_time"])
            total["누적_반환행"] += int(value["rows"])
            total["통계_행수"] += 1
        except (KeyError, TypeError, ValueError, json.JSONDecodeError):
            continue
    return [totals[kind] for kind in sorted(totals)]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--zone", required=True)
    parser.add_argument("--ssh-key-file", required=True)
    parser.add_argument("--container", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    role_check = "psql -U postgres -d postgres -Atc 'SELECT pg_is_in_recovery()'"
    psql = f"psql -U postgres -d postgres -Atc {shlex.quote(SQL)}"
    remote = (f"sudo docker exec -u opensql {shlex.quote(args.container)} "
              f"bash -lc {shlex.quote(role_check + ' && ' + psql)}")
    command = ["gcloud", "compute", "ssh", args.instance,
               f"--project={args.project}", f"--zone={args.zone}",
               f"--ssh-key-file={args.ssh_key_file}", "--quiet", f"--command={remote}"]
    result = subprocess.run(command, capture_output=True, text=True, timeout=45,
                            check=False)
    if result.returncode != 0:
        # stderr can contain public IPs, account names, and SSH paths.
        raise RuntimeError(f"DB 통계 조회 실패: 원격 종료 코드 {result.returncode}")
    role, separator, raw_statistics = result.stdout.partition("\n")
    if not separator or role.strip() != "f":
        raise RuntimeError("선택한 DB 노드가 primary임을 확인하지 못했습니다.")
    artifact = {
        "run_id": args.run_id,
        "시각_KST": datetime.now(KST).isoformat(),
        "환경": "GCP OpenSQL primary",
        "쿼리별_누적값": safe_rows(raw_statistics, args.database),
    }
    with Path(args.output).open("x", encoding="utf-8") as output:
        json.dump(artifact, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps({"run_id": args.run_id, "matching_queries": len(artifact["쿼리별_누적값"])},
                     ensure_ascii=False))


if __name__ == "__main__":
    main()
