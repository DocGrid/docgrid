#!/usr/bin/env python3
"""Validate invariants shared by DocGrid's provisioned Grafana dashboard."""

from __future__ import annotations

import json
import sys
from pathlib import Path


DASHBOARD_UID = "docgrid-operations"
DATASOURCE_UID = "docgrid-prometheus"
EXPECTED_VARIABLES = {"cluster", "environment", "instance"}
EXPECTED_ROWS = {
    "전체 상태",
    "Embedding Queue",
    "Embedding Circuit",
    "Embedding Provider",
    "RAG Queue",
    "Sync Outbox",
    "Metric 수집 상태",
}
REQUIRED_METRICS = {
    "docgrid_embedding_claimable_jobs",
    "docgrid_embedding_delayed_retry_jobs",
    "docgrid_embedding_provider_circuit_open",
    "docgrid_embedding_provider_circuit_probe_failed",
    "docgrid_rag_processing_jobs",
    "docgrid_sync_outbox_claimable_events",
    "docgrid_operational_snapshot_age_seconds",
    "embedding_provider_model_loaded",
}


def fail(message: str) -> None:
    raise SystemExit(f"Grafana dashboard validation failed: {message}")


def validate(path: Path) -> None:
    dashboard = json.loads(path.read_text(encoding="utf-8"))
    if dashboard.get("uid") != DASHBOARD_UID:
        fail(f"expected dashboard uid {DASHBOARD_UID}")
    if dashboard.get("editable") is not False:
        fail("provisioned dashboard must be read-only")

    # 1. 선택 범위를 제한하는 세 변수는 모든 운영 Panel이 공유한다.
    variables = dashboard.get("templating", {}).get("list", [])
    variable_names = {variable.get("name") for variable in variables}
    if variable_names != EXPECTED_VARIABLES:
        fail(f"unexpected variables: {sorted(variable_names)}")
    for variable in variables:
        if variable.get("datasource", {}).get("uid") != DATASOURCE_UID:
            fail(f"variable {variable.get('name')} uses another data source")

    # 2. Panel ID와 Grid 영역이 겹치면 Provisioning은 성공해도 화면을 읽기 어렵다.
    panels = dashboard.get("panels", [])
    panel_ids = [panel.get("id") for panel in panels]
    if len(panel_ids) != len(set(panel_ids)):
        fail("panel ids must be unique")
    row_titles = {panel.get("title") for panel in panels if panel.get("type") == "row"}
    if row_titles != EXPECTED_ROWS:
        fail(f"unexpected rows: {sorted(row_titles)}")

    occupied: set[tuple[int, int]] = set()
    expressions: list[str] = []
    for panel in panels:
        grid = panel.get("gridPos", {})
        for x in range(grid.get("x", 0), grid.get("x", 0) + grid.get("w", 0)):
            for y in range(grid.get("y", 0), grid.get("y", 0) + grid.get("h", 0)):
                coordinate = (x, y)
                if coordinate in occupied:
                    fail(f"panel {panel.get('title')} overlaps another panel at {coordinate}")
                occupied.add(coordinate)

        if panel.get("type") == "row":
            continue
        if not panel.get("description"):
            fail(f"panel {panel.get('title')} has no interpretation description")
        if panel.get("datasource", {}).get("uid") != DATASOURCE_UID:
            fail(f"panel {panel.get('title')} uses another data source")
        targets = panel.get("targets", [])
        if not targets:
            fail(f"panel {panel.get('title')} has no Prometheus query")
        for target in targets:
            if target.get("datasource", {}).get("uid") != DATASOURCE_UID:
                fail(f"target in {panel.get('title')} uses another data source")
            expression = target.get("expr", "")
            if not expression:
                fail(f"target in {panel.get('title')} has an empty expression")
            expressions.append(expression)

    # 3. 핵심 장애 판단 Metric이 빠지면 Dashboard가 일부 정상처럼 보일 수 있다.
    joined_expressions = "\n".join(expressions)
    missing_metrics = sorted(metric for metric in REQUIRED_METRICS if metric not in joined_expressions)
    if missing_metrics:
        fail(f"required metrics are missing: {', '.join(missing_metrics)}")

    print(
        "Grafana dashboard validation: SUCCESS "
        f"({len(panels)} panels, {len(expressions)} PromQL expressions)"
    )


if __name__ == "__main__":
    dashboard_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(
        "monitoring/grafana/dashboards/docgrid-operations.json"
    )
    validate(dashboard_path)
