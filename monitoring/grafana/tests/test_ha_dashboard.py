"""Check the HA demo dashboard's evidence and layout boundaries."""

from __future__ import annotations

import json
import unittest
from pathlib import Path


DASHBOARD = Path(__file__).resolve().parents[1] / "dashboards" / "docgrid-ha-demo.json"


class HaDashboardTest(unittest.TestCase):
    """Keep the HA panels readable without treating graphs as DB reconciliation."""

    @classmethod
    def setUpClass(cls):
        """Load the committed dashboard once for deterministic static checks."""
        cls.dashboard = json.loads(DASHBOARD.read_text(encoding="utf-8"))

    def test_datasource_and_access_scope(self):
        """All queries use the observer's authenticated, private data source."""
        self.assertEqual("docgrid-opensql-ha-live", self.dashboard["uid"])
        self.assertFalse(self.dashboard["editable"])
        panels = [panel for panel in self.dashboard["panels"] if panel["type"] != "row"]
        self.assertTrue(panels)
        for panel in panels:
            self.assertEqual("ha-prometheus", panel["datasource"]["uid"])
            self.assertTrue(panel["description"])
            for target in panel["targets"]:
                self.assertEqual("ha-prometheus", target["datasource"]["uid"])
                self.assertTrue(target["expr"])

    def test_panel_grid_does_not_overlap(self):
        """Each panel owns an exclusive rectangle in the Grafana grid."""
        occupied = set()
        ids = set()
        for panel in self.dashboard["panels"]:
            self.assertNotIn(panel["id"], ids)
            ids.add(panel["id"])
            grid = panel["gridPos"]
            for x in range(grid["x"], grid["x"] + grid["w"]):
                for y in range(grid["y"], grid["y"] + grid["h"]):
                    self.assertNotIn((x, y), occupied)
                    occupied.add((x, y))

    def test_outcome_buckets_include_other_and_unknown(self):
        """An HTTP 502/504 or unknown result must not disappear from the view."""
        expressions = "\n".join(
            target["expr"]
            for panel in self.dashboard["panels"]
            for target in panel.get("targets", [])
        )
        for name in (
            "k6_ha_outcome_201_total",
            "k6_ha_outcome_500_total",
            "k6_ha_outcome_503_total",
            "k6_ha_outcome_other_failed_total",
            "k6_ha_outcome_unknown_total",
        ):
            self.assertIn(name, expressions)
        self.assertNotIn("histogram_quantile", expressions)
        self.assertNotIn("p95", expressions)

    def test_http_class_cards_keep_received_statuses_separate_from_unknown(self):
        """A selected run exposes four cumulative code classes alongside rates."""
        panels = {panel["id"]: panel for panel in self.dashboard["panels"]}
        for panel_id, name in zip((15, 16, 17, 18), ("2xx", "3xx", "4xx", "5xx")):
            panel = panels[panel_id]
            self.assertEqual("stat", panel["type"])
            self.assertTrue(panel["targets"][0]["instant"])
            self.assertIn(f"k6_ha_http_{name}_total", panel["targets"][0]["expr"])
            self.assertIn('run_id=~"$run_id"', panel["targets"][0]["expr"])
            self.assertIn("누적", panel["title"])
            self.assertEqual("none", panel["fieldConfig"]["defaults"]["unit"])
        self.assertIn("결과 불명", self.dashboard["description"])
        self.assertLess(panels[15]["gridPos"]["y"], panels[7]["gridPos"]["y"])

    def test_run_filter_and_interpretation(self):
        """A recorded run is selectable, and RPO remains an external-DB claim."""
        variables = self.dashboard["templating"]["list"]
        self.assertEqual(["run_id"], [variable["name"] for variable in variables])
        self.assertIn("run_id=~", json.dumps(self.dashboard["panels"]))
        self.assertIn("요청 ID 원장과 DB 대조", self.dashboard["description"])
        self.assertEqual(2, variables[0]["refresh"])

    def test_wal_panel_excludes_primary(self):
        """Only replicas have a meaningful receive-versus-replay backlog."""
        panel = next(panel for panel in self.dashboard["panels"] if panel["id"] == 5)
        expression = panel["targets"][0]["expr"]
        self.assertIn("patroni_xlog_received_location", expression)
        self.assertIn("and on(instance)", expression)
        self.assertIn("patroni_replica", expression)

    def test_state_timelines_distinguish_scrape_and_postgres_failure(self):
        """A failed scrape must not be described as a proven VM outage."""
        panels = {panel["id"]: panel for panel in self.dashboard["panels"]}
        app = panels[2]
        database = panels[3]
        self.assertEqual("state-timeline", app["type"])
        self.assertEqual("state-timeline", database["type"])
        self.assertEqual('up{job="docgrid-app",node="app-A"}', app["targets"][0]["expr"])
        self.assertEqual('up{job="docgrid-app",node="app-B"}', app["targets"][1]["expr"])
        self.assertIn("patroni_postgres_running", database["targets"][0]["expr"])
        self.assertIn('up{job="patroni"}', database["targets"][0]["expr"])
        self.assertEqual(
            "수집 실패",
            database["fieldConfig"]["defaults"]["mappings"][0]["options"]["-1"]["text"],
        )
        self.assertIn("단정하지 않는다", database["description"])
        self.assertEqual("orange", database["fieldConfig"]["defaults"]["mappings"][0]["options"]["2"]["color"])

    def test_primary_cards_only_use_current_primary(self):
        """A past or replica timeline must not impersonate the current leader."""
        panels = {panel["id"]: panel for panel in self.dashboard["panels"]}
        for panel_id in (9, 10):
            self.assertEqual("stat", panels[panel_id]["type"])
            self.assertTrue(panels[panel_id]["targets"][0]["instant"])
            self.assertIn('patroni_primary{job="patroni"} == 1', panels[panel_id]["targets"][0]["expr"])
        self.assertIn("and on(instance)", panels[10]["targets"][0]["expr"])
        self.assertNotIn("max(", panels[10]["targets"][0]["expr"])

    def test_compact_status_layout_and_proxy_probe_scope(self):
        """Top status panels stay compact and label TCP reachability honestly."""
        panels = {panel["id"]: panel for panel in self.dashboard["panels"]}
        for panel_id in (9, 10, 13):
            self.assertLessEqual(panels[panel_id]["gridPos"]["h"], 3)
            self.assertEqual(8, panels[panel_id]["gridPos"]["w"])
        for panel_id in (2, 3, 14):
            self.assertEqual(6, panels[panel_id]["gridPos"]["h"])
            self.assertEqual(8, panels[panel_id]["gridPos"]["w"])
        self.assertEqual("state-timeline", panels[14]["type"])
        self.assertIn('probe_success{job="openproxy-tcp",node="proxy-A"}', panels[14]["targets"][0]["expr"])
        self.assertIn("db-node2", panels[14]["targets"][0]["expr"])
        self.assertIn("db-node3", panels[14]["targets"][0]["expr"])
        self.assertIn("SQL", panels[14]["description"])
        self.assertIn("관측 VM", panels[14]["title"])
        self.assertEqual('sum(probe_success{job="openproxy-tcp"})', panels[13]["targets"][0]["expr"])
        self.assertEqual("2 / 2", panels[13]["fieldConfig"]["defaults"]["mappings"][0]["options"]["2"]["text"])
        self.assertEqual("red", panels[13]["fieldConfig"]["defaults"]["mappings"][0]["options"]["1"]["color"])

    def test_app_error_and_pool_timeout_cannot_replace_k6_result(self):
        """App-local failures remain separate from client-observed outcomes."""
        panels = {panel["id"]: panel for panel in self.dashboard["panels"]}
        self.assertIn("http_server_requests_seconds_count", panels[11]["targets"][0]["expr"])
        self.assertIn("LB", panels[11]["description"])
        self.assertIn("hikaricp_connections_timeout_total", panels[12]["targets"][0]["expr"])
        self.assertIn("[15s]", panels[12]["targets"][0]["expr"])
        self.assertIn("axisSoftMax", panels[12]["fieldConfig"]["defaults"]["custom"])
        self.assertEqual("never", panels[7]["fieldConfig"]["defaults"]["custom"]["showPoints"])
        self.assertIn("k6_ha_outcome_500_total", panels[7]["targets"][1]["expr"])

    def test_annotation_source_is_explicit_fault_event(self):
        """Show recorded fault events rather than a marker for every failed scrape."""
        annotations = self.dashboard["annotations"]["list"]
        self.assertEqual(1, len(annotations))
        self.assertEqual("-- Grafana --", annotations[0]["datasource"]["uid"])
        self.assertTrue(annotations[0]["enable"])


if __name__ == "__main__":
    unittest.main()
