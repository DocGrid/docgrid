"""Check that HA failure summaries are correct and contain no request-level data."""

import csv
import gzip
import json
import tempfile
import unittest
from pathlib import Path

from analyze_ha_http_failures import FIELDS, analyze


class AnalyzeHaHttpFailuresTest(unittest.TestCase):
    """Exercise fault-marker boundaries and evidence-consistency guards with synthetic IDs."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.run_dir = Path(self.temporary.name)
        (self.run_dir / "connection-recovery.json").write_text(json.dumps({
            "kill_command_returned_at_utc": "2026-10-02T00:00:00.000Z",
            "http": {"failed_count": 2},
        }), encoding="utf-8")
        (self.run_dir / "reconciliation.json").write_text(json.dumps({
            "failed_count": 2,
            "failed_persisted_count": 0,
        }), encoding="utf-8")

    def write_rows(self, rows):
        with gzip.open(self.run_dir / "requests.csv.gz", "wt", encoding="utf-8", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(rows)

    @staticmethod
    def row(request_id, sent_at, completed_at):
        return {"request_id": request_id, "operation": "ha_probe_write", "sent_at": sent_at,
                "completed_at": completed_at, "outcome": "FAILED", "http_status": "500",
                "reason": "sensitive-text-must-never-be-output"}

    def test_counts_requests_on_both_sides_of_fault_marker_without_exposing_ids(self):
        self.write_rows([
            self.row("secret-request-before", "2026-10-01T23:59:59.990Z", "2026-10-02T00:00:00.040Z"),
            self.row("secret-request-after", "2026-10-02T00:00:00.020Z", "2026-10-02T00:00:00.080Z"),
        ])

        result = analyze(self.run_dir)

        self.assertEqual(result["http_500_count"], 2)
        self.assertEqual(result["sent_before_kill_marker"], 1)
        self.assertEqual(result["sent_at_or_after_kill_marker"], 1)
        self.assertEqual(result["sent_offset_ms_min_max"], [-10.0, 20.0])
        self.assertEqual(result["failed_persisted_count"], 0)
        self.assertNotIn("secret-request", json.dumps(result))
        self.assertNotIn("sensitive-text", json.dumps(result))

    def test_rejects_disagreement_with_external_ledger(self):
        self.write_rows([self.row("one", "2026-10-02T00:00:00.010Z", "2026-10-02T00:00:00.020Z")])

        with self.assertRaisesRegex(ValueError, "disagree"):
            analyze(self.run_dir)

    def test_rejects_unlisted_ledger_column(self):
        with gzip.open(self.run_dir / "requests.csv.gz", "wt", encoding="utf-8") as target:
            target.write(",".join((*FIELDS, "internal_ip")) + "\n")

        with self.assertRaisesRegex(ValueError, "allowlist"):
            analyze(self.run_dir)


if __name__ == "__main__":
    unittest.main()
