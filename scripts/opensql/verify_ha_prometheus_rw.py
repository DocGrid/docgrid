#!/usr/bin/env python3
"""Compare a run's final Prometheus counts with its sanitized client ledger.

Intermediate dashboard delivery and database effects remain separate verdicts.
"""

from __future__ import annotations

import argparse
import ipaddress
import json
import math
import os
import sys
import time
from collections import Counter
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from urllib.request import ProxyHandler, Request, build_opener

from sanitize_ha_k6_events import RUN_ID, safe_event


OUTCOMES = ("201", "500", "503", "other_failed", "unknown")
HTTP_CLASSES = ("2xx", "3xx", "4xx", "5xx")
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(cidr) for cidr in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"
))


def query_base(write_url: str) -> str:
    """Allow only a private IPv4 Prometheus receiver without credentials."""
    parsed = urlsplit(write_url)
    try:
        address = ipaddress.ip_address(parsed.hostname or "")
        port = parsed.port
    except ValueError as error:
        raise ValueError("관측 서버 주소 형식이 올바르지 않습니다") from error
    if (parsed.scheme != "http" or address.version != 4 or
            not any(address in network for network in PRIVATE_NETWORKS) or
            port != 9090 or parsed.path != "/api/v1/write" or
            parsed.username or parsed.password or parsed.query or parsed.fragment):
        raise ValueError("관측 서버는 사설 IPv4의 9090 원격 쓰기 경로여야 합니다")
    return f"http://{address}:9090/api/v1/query"


def expected_counts(events_path: Path, run_id: str) -> dict[str, int]:
    """Count terminal outcomes and received HTTP classes in the safe ledger."""
    if not RUN_ID.fullmatch(run_id):
        raise ValueError("실행 ID가 올바르지 않습니다")
    seen: dict[str, set[str]] = {}
    counts: Counter[str] = Counter()
    with events_path.open(encoding="utf-8") as source:
        for line in source:
            item = safe_event(json.loads(line), run_id)
            request_id = item["request_id"]
            kind = item["kind"]
            kinds = seen.setdefault(request_id, set())
            if (kind == "sent" and kinds) or (kind != "sent" and kinds != {"sent"}):
                raise ValueError("요청 원장에 중복 종료 이벤트가 있습니다")
            kinds.add(kind)
            if kind == "acknowledged":
                counts["201"] += 1
                counts["http_2xx"] += 1
            elif kind == "failed":
                status = item["http_status"]
                counts[str(status) if status in (500, 503) else "other_failed"] += 1
                counts[f"http_{status // 100}xx"] += 1
            elif kind == "unknown":
                counts["unknown"] += 1
    if not seen or any(len(kinds) != 2 or "sent" not in kinds for kinds in seen.values()):
        raise ValueError("요청 원장에 전송 또는 종료 이벤트가 빠졌습니다")
    return {name: counts[name] for name in OUTCOMES} | {
        f"http_{name}": counts[f"http_{name}"] for name in HTTP_CLASSES
    }


def expected_outcomes(events_path: Path, run_id: str) -> dict[str, int]:
    """Retain the outcome-only view for existing callers."""
    counts = expected_counts(events_path, run_id)
    return {name: counts[name] for name in OUTCOMES}


def remote_count(query_url: str, run_id: str, outcome: str) -> int:
    """Read the last non-stale counter in a bounded window after final flush."""
    metric = (f"k6_ha_{outcome}_total" if outcome.startswith("http_")
              else f"k6_ha_outcome_{outcome}_total")
    expression = f'last_over_time({metric}{{run_id="{run_id}"}}[10m])'
    url = f"{query_url}?{urlencode({'query': expression})}"
    # 1. Do not send a private endpoint through local proxy environment variables.
    opener = build_opener(ProxyHandler({}))
    with opener.open(Request(url, headers={"Accept": "application/json"}), timeout=5) as response:
        payload = json.load(response)
    if payload.get("status") != "success" or payload.get("data", {}).get("resultType") != "vector":
        raise ValueError("관측 서버 응답 형식이 올바르지 않습니다")
    results = payload["data"]["result"]
    if not results:
        return 0
    if len(results) != 1:
        raise ValueError("실행 ID에 지표 시계열이 여러 개 있습니다")
    value = float(results[0]["value"][1])
    if not math.isfinite(value) or value < 0 or not value.is_integer():
        raise ValueError("관측 건수가 올바르지 않습니다")
    return int(value)


def verify(events_path: Path, run_id: str, write_url: str) -> dict:
    """Compare dashboard totals with the ledger, not with final DB rows."""
    query_url = query_base(write_url)
    expected = expected_counts(events_path, run_id)
    # 2. Allow a bounded ingestion delay after k6's final remote-write flush.
    for attempt in range(1, 5):
        observed = {name: remote_count(query_url, run_id, name) for name in expected}
        if observed == expected or attempt == 4:
            break
        time.sleep(2)
    return {
        "run_id": run_id,
        "expected": expected,
        "observed": observed,
        "query_attempts": attempt,
        "final_totals_match": expected == observed,
        "intermediate_delivery_verified": False,
        "db_reconciliation_verified": False,
    }


def main() -> int:
    """Print only allowlisted counts, never the internal URL or raw HTTP error."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("validate-url", "verify"))
    parser.add_argument("--events", type=Path)
    parser.add_argument("--run-id")
    args = parser.parse_args()
    write_url = os.environ.get("HA_PROM_RW_URL", "")
    try:
        if args.action == "validate-url":
            query_base(write_url)
            return 0
        if args.events is None or args.run_id is None:
            raise ValueError("원장 또는 실행 ID가 없습니다")
        result = verify(args.events, args.run_id, write_url)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        print("원격 지표 검증 실패: 주소·원장·응답을 확인하세요", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if result["final_totals_match"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
