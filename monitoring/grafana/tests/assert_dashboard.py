#!/usr/bin/env python3
"""Verify Grafana provisioning and execute every dashboard PromQL expression."""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


EXPECTED_UID = "docgrid-operations"
EXPECTED_DATASOURCE_UID = "docgrid-prometheus"


def request_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.load(response)


def wait_json(url: str, predicate, description: str, timeout_seconds: int = 60) -> dict:
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            payload = request_json(url)
            if predicate(payload):
                return payload
        except (OSError, urllib.error.HTTPError, json.JSONDecodeError) as error:
            last_error = error
        time.sleep(1)
    raise AssertionError(f"{description} 대기 실패: {last_error}")


def prometheus_query(base_url: str, expression: str) -> list[dict]:
    encoded = urllib.parse.urlencode({"query": expression})
    url = (
        f"{base_url}/api/datasources/proxy/uid/{EXPECTED_DATASOURCE_UID}"
        f"/api/v1/query?{encoded}"
    )
    payload = request_json(url)
    if payload.get("status") != "success":
        raise AssertionError(f"PromQL 실행 실패: {expression}: {payload}")
    return payload.get("data", {}).get("result", [])


def concrete_expression(expression: str) -> str:
    return (
        expression.replace('$cluster', 'docgrid-test')
        .replace('$environment', 'test')
        .replace('$instance', '.*')
        .replace('$__rate_interval', '1m')
    )


def main(base_url: str) -> None:
    # 1. Grafana DB와 HTTP Server가 준비될 때까지 실제 Health API를 확인한다.
    health = wait_json(
        f"{base_url}/api/health",
        lambda payload: payload.get("database") == "ok",
        "Grafana Health",
    )
    print(f"[성공] Grafana Health: database={health['database']}")

    # 2. File Provisioning이 Data Source와 Dashboard를 실제 DB에 등록했는지 확인한다.
    datasource = wait_json(
        f"{base_url}/api/datasources/uid/{EXPECTED_DATASOURCE_UID}",
        lambda payload: payload.get("uid") == EXPECTED_DATASOURCE_UID,
        "Prometheus Data Source Provisioning",
    )
    if datasource.get("url") != "http://prometheus:9090":
        raise AssertionError(f"예상하지 않은 Prometheus URL: {datasource.get('url')}")
    print(f"[성공] Data Source Provisioning: uid={datasource['uid']}")

    response = wait_json(
        f"{base_url}/api/dashboards/uid/{EXPECTED_UID}",
        lambda payload: payload.get("dashboard", {}).get("uid") == EXPECTED_UID,
        "Dashboard Provisioning",
    )
    dashboard = response["dashboard"]
    panels = dashboard.get("panels", [])
    variables = dashboard.get("templating", {}).get("list", [])
    if {variable.get("name") for variable in variables} != {"cluster", "environment", "instance"}:
        raise AssertionError("Dashboard 변수 Provisioning 결과가 예상과 다릅니다.")
    print(f"[성공] Dashboard Provisioning: uid={dashboard['uid']}, panels={len(panels)}")

    # 3. Grafana Data Source Proxy를 경유해 실제 Fixture 시계열이 조회되는지 확인한다.
    claimable = wait_json(
        f"{base_url}/api/datasources/proxy/uid/{EXPECTED_DATASOURCE_UID}/api/v1/query?"
        + urllib.parse.urlencode(
            {"query": 'docgrid_embedding_claimable_jobs{cluster="docgrid-test",environment="test"}'}
        ),
        lambda payload: bool(payload.get("data", {}).get("result")),
        "Prometheus Fixture Scrape",
    )
    value = float(claimable["data"]["result"][0]["value"][1])
    if value != 4:
        raise AssertionError(f"claimable fixture 값이 4가 아닙니다: {value}")
    print(f"[성공] Grafana→Prometheus 실제 조회: claimable_jobs={value:.0f}")

    # 4. Dashboard의 모든 PromQL을 변수 치환 후 실행해 문법과 Label Matcher를 검증한다.
    expressions = [
        target["expr"]
        for panel in panels
        for target in panel.get("targets", [])
        if target.get("expr")
    ]
    for expression in expressions:
        prometheus_query(base_url, concrete_expression(expression))
    print(f"[성공] Dashboard PromQL 실행: {len(expressions)}개")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: assert_dashboard.py http://127.0.0.1:PORT")
    main(sys.argv[1].rstrip("/"))
