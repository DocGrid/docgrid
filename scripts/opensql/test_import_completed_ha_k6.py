#!/usr/bin/env python3
"""Check retrospective HA ledger imports without contacting GCP or PostgreSQL."""

from __future__ import annotations

import argparse
import json
import tempfile
import unittest
from pathlib import Path

from ha_evidence import EvidenceError
from import_completed_ha_k6 import archive


class CompletedK6ImportTest(unittest.TestCase):
    """Keep original timestamps and request-level DB reconciliation intact."""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.events = self.root / "safe-events.jsonl"
        self.summary = self.root / "k6-summary.json"
        self.db_csv = self.root / "db-counts.csv"
        self.config = self.root / "redacted-config.json"
        self.output = self.root / "ledger"
        self.config.write_text('{"failsafe_mode":true}\n', encoding="utf-8")
        self.summary.write_text(json.dumps({
            "run_id": "e428unit01", "iterations": 1, "http_requests": 1,
            "dropped_iterations": 0, "failed_rate": 0,
        }), encoding="utf-8")
        self.db_csv.write_text("request_id,row_count\ne428unit01-v1-i0,1\n",
                               encoding="utf-8")
        self.events.write_text("\n".join(json.dumps(event) for event in (
            {
                "event_id": "sent-1", "run_id": "e428unit01",
                "at": "2026-10-04T07:18:00.001Z", "kind": "sent",
                "request_id": "e428unit01-v1-i0", "operation": "ha_probe_write",
            },
            {
                "event_id": "ack-1", "run_id": "e428unit01",
                "at": "2026-10-04T07:18:00.040Z", "kind": "acknowledged",
                "request_id": "e428unit01-v1-i0", "http_status": 201,
            },
        )) + "\n", encoding="utf-8")

    def args(self) -> argparse.Namespace:
        return argparse.Namespace(
            events=self.events, k6_summary=self.summary, db_csv=self.db_csv,
            config_file=self.config, output=self.output, run_id="e428unit01",
            scenario="unit", opensql_version="v3.17.8.7",
            openproxy_version="1.1.3", patroni_version="4.0.5",
            etcd_version="3.6.5",
        )

    def test_preserves_real_run_times_and_reconciles_one_success(self) -> None:
        result = archive(self.args())
        manifest = json.loads((self.output / "manifest.json").read_text())
        summary = json.loads((self.output / "summary.json").read_text())
        self.assertEqual("2026-10-04T07:18:00.001Z", manifest["started_at"])
        self.assertEqual("2026-10-04T07:18:00.040Z", summary["finished_at"])
        self.assertTrue(result["normal_baseline_pass"])
        self.assertEqual(0, result["acknowledged_missing_count"])

    def test_rejects_unlisted_sensitive_event_field(self) -> None:
        self.events.write_text(self.events.read_text().replace(
            '"operation": "ha_probe_write"', '"operation": "ha_probe_write", "token": "forbidden"'
        ), encoding="utf-8")
        with self.assertRaises(EvidenceError):
            archive(self.args())
        self.assertFalse((self.output / "requests.csv").exists())

    def test_rejects_non_object_event(self) -> None:
        self.events.write_text('[]\n', encoding="utf-8")
        with self.assertRaises(EvidenceError):
            archive(self.args())
        self.assertFalse(self.output.exists())

    def test_reports_acknowledged_row_missing_from_db(self) -> None:
        self.db_csv.write_text("request_id,row_count\n", encoding="utf-8")
        result = archive(self.args())
        self.assertEqual(1, result["acknowledged_missing_count"])
        self.assertFalse(result["normal_baseline_pass"])


if __name__ == "__main__":
    unittest.main()
