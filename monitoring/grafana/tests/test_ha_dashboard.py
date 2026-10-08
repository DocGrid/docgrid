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

    def test_run_filter_and_interpretation(self):
        """A recorded run is selectable, and RPO remains an external-DB claim."""
        variables = self.dashboard["templating"]["list"]
        self.assertEqual(["run_id"], [variable["name"] for variable in variables])
        self.assertIn("run_id=~", json.dumps(self.dashboard["panels"]))
        self.assertIn("요청 ID 원장과 DB 대조", self.dashboard["description"])

    def test_wal_panel_excludes_primary(self):
        """Only replicas have a meaningful receive-versus-replay backlog."""
        panel = next(panel for panel in self.dashboard["panels"] if panel["id"] == 5)
        expression = panel["targets"][0]["expr"]
        self.assertIn("patroni_xlog_received_location", expression)
        self.assertIn("and on(instance)", expression)
        self.assertIn("patroni_replica", expression)


if __name__ == "__main__":
    unittest.main()
