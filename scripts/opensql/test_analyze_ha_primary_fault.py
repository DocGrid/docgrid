#!/usr/bin/env python3
"""Regression checks for request-start-based primary fault recovery summaries."""

import csv
import json
import tempfile
import unittest
from pathlib import Path

from analyze_ha_primary_fault import analyze


class PrimaryFaultAnalysisTest(unittest.TestCase):
    """Keep fault timing distinct from client failure and stable recovery."""

    def test_requires_five_full_successful_seconds_after_last_bad_start(self):
        """A single successful request does not end the observed outage."""
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary) / "ha420synthetic"
            run_dir.mkdir()
            (run_dir / "events.jsonl").write_text(json.dumps({
                "kind": "fault", "phase": "start", "at": "2026-10-03T00:00:00.500Z"
            }) + "\n", encoding="utf-8")
            rows = [
                ("2026-10-03T00:00:00.100Z", "ACKNOWLEDGED"),
                ("2026-10-03T00:00:01.100Z", "FAILED"),
            ] + [(f"2026-10-03T00:00:0{second}.100Z", "ACKNOWLEDGED")
                 for second in range(2, 7)]
            with (run_dir / "requests.csv").open("w", newline="", encoding="utf-8") as output:
                writer = csv.DictWriter(output, fieldnames=("sent_at", "outcome"))
                writer.writeheader()
                for sent_at, outcome in rows:
                    writer.writerow({"sent_at": sent_at, "outcome": outcome})

            result = analyze(run_dir)

            self.assertEqual(1.5, result["fault_to_stable_recovery_seconds"])
            self.assertEqual(1.9, result["observed_write_gap_seconds"])
            self.assertEqual(1, result["non_ack_count"])

    def test_rejects_a_run_without_fault_injection(self):
        """An aborted baseline cannot be mislabeled as a VM fault result."""
        with tempfile.TemporaryDirectory() as temporary:
            run_dir = Path(temporary)
            (run_dir / "events.jsonl").write_text("", encoding="utf-8")
            (run_dir / "requests.csv").write_text("sent_at,outcome\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "one fault start"):
                analyze(run_dir)


if __name__ == "__main__":
    unittest.main()
