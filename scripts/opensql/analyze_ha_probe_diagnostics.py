#!/usr/bin/env python3
"""Join a verified HA request ledger to already-sanitized per-app failure events."""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter
from pathlib import Path

from ha_evidence import EvidenceError, replace_private, verify


SAFE_CLASS = re.compile(r"[A-Za-z][A-Za-z0-9]{0,79}\Z")
SAFE_STATE = re.compile(r"(?:[A-Z0-9]{5}|none)\Z")
PHASES = {"AUTHORIZATION", "TX_BEGIN", "SQL_EXECUTE", "COMMIT_PENDING"}


def analyze(run_dir: Path, app_logs: list[Path]) -> dict:
    """Count only exact run-scoped events; expose no request IDs in the derived report."""
    run_id = verify(run_dir)["run_id"]
    with (run_dir / "requests.csv").open(newline="", encoding="utf-8") as source:
        requests = {row["request_id"]: row for row in csv.DictReader(source)}
    failures = {key for key, value in requests.items() if value["http_status"] == "500"}
    exception_ids: set[str] = set()
    result_ids: set[str] = set()
    throw_ids: set[str] = set()
    classes: Counter[tuple[str, str, str]] = Counter()
    per_app: dict[str, int] = {}
    for index, path in enumerate(app_logs, 1):
        count = 0
        with path.open(encoding="utf-8") as source:
            for line in source:
                event = json.loads(line)
                if set(event) - {"at", "event", "run_id", "request_id", "phase", "type", "sqlstate", "status"}:
                    raise EvidenceError("앱 진단 로그에 허용되지 않은 필드가 있습니다")
                request_id = event.get("request_id")
                if event.get("run_id") != run_id or request_id not in requests or event.get("phase") not in PHASES:
                    raise EvidenceError("앱 진단 로그의 실행·요청·단계가 원장과 다릅니다")
                kind = event.get("event")
                if kind in {"EXCEPTION", "THROW"}:
                    error_type, sqlstate = event.get("type"), event.get("sqlstate")
                    if not isinstance(error_type, str) or not SAFE_CLASS.fullmatch(error_type) or \
                       not isinstance(sqlstate, str) or not SAFE_STATE.fullmatch(sqlstate):
                        raise EvidenceError("앱 예외 유형 또는 SQLSTATE가 허용 형식이 아닙니다")
                    classes[(event["phase"], error_type, sqlstate)] += 1
                    target = exception_ids if kind == "EXCEPTION" else throw_ids
                elif kind == "RESULT" and event.get("status") == 500:
                    target = result_ids
                else:
                    raise EvidenceError("앱 진단 이벤트 종류 또는 상태가 올바르지 않습니다")
                if request_id in target:
                    raise EvidenceError("같은 요청의 앱 진단 이벤트가 중복됐습니다")
                target.add(request_id)
                count += 1
        per_app[f"app_{index}"] = count
    return {
        "run_id": run_id,
        "client_http_500": len(failures),
        "matched_exception_500": len(failures & exception_ids),
        "matched_result_500": len(failures & result_ids),
        "matched_throw_500": len(failures & throw_ids),
        "client_500_without_app_diagnostic": len(failures - (exception_ids | result_ids | throw_ids)),
        "app_diagnostic_without_client_500": len((exception_ids | result_ids | throw_ids) - failures),
        "per_app_event_count": per_app,
        "error_classes": [
            {"phase": phase, "type": error_type, "sqlstate": sqlstate, "count": count}
            for (phase, error_type, sqlstate), count in sorted(classes.items())
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--app-log", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = analyze(args.run_dir, args.app_log)
        replace_private(args.output, json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    except (EvidenceError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(f"진단 대조 실패: {error}")
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
