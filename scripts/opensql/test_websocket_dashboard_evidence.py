"""Check that the archived WebSocket benchmark evidence remains complete and parseable."""

from __future__ import annotations

import json
from pathlib import Path
from statistics import median
import unittest


EVIDENCE = (
    Path(__file__).resolve().parents[2]
    / "docs/test-results/evidence/issue-374"
)
RUNS = EVIDENCE / "runs"
METRICS = EVIDENCE / "metrics"
LOADS = (1, 5, 20, 50)
REPEATS = ("", "-r2", "-r3")


class WebsocketDashboardEvidenceTest(unittest.TestCase):
    """Verify run separation, parseable samples, and the published 50-user comparison."""

    def test_each_version_and_load_has_a_separate_successful_run(self):
        """No failed client or interior sequence gap is hidden in a summary."""
        expected = {
            f"{version}-n{load}{repeat}.json"
            for version in ("base", "fix")
            for load in LOADS
            for repeat in (REPEATS if load == 50 else ("",))
        }
        self.assertEqual(expected, {path.name for path in RUNS.glob("*.json")})

        for name in expected:
            with self.subTest(run=name):
                record = json.loads((RUNS / name).read_text(encoding="utf-8"))
                self.assertEqual(record["clients_requested"], record["clients_connected"])
                self.assertEqual([], record["client_errors"])
                self.assertEqual(0, record["interior_sequence_gaps"])
                self.assertGreater(record["frames_received"], 0)
                self.assertGreater(record["latency_ms"]["p95"], 0)

    def test_50_subscriber_medians_match_the_report(self):
        """Published medians come from three runs, not a single favorable sample."""
        summaries = {}
        for version in ("base", "fix"):
            summaries[version] = [
                json.loads((RUNS / f"{version}-n50{repeat}.json").read_text(encoding="utf-8"))
                for repeat in REPEATS
            ]

        self.assertAlmostEqual(
            8.475, median(run["latency_ms"]["p95"] for run in summaries["base"])
        )
        self.assertAlmostEqual(
            2443.748, median(run["latency_ms"]["p95"] for run in summaries["fix"])
        )
        self.assertEqual(5000, median(run["frames_received"] for run in summaries["base"]))
        self.assertEqual(588, median(run["frames_received"] for run in summaries["fix"]))

    def test_metrics_are_separate_and_time_ordered(self):
        """Only the single-subscriber smoke runs lack app metric series."""
        expected = {
            path.stem + "-metrics.jsonl"
            for path in RUNS.glob("*.json")
            if "-n1" not in path.stem
        }
        self.assertEqual(expected, {path.name for path in METRICS.glob("*.jsonl")})

        for name in expected:
            with self.subTest(metrics=name):
                records = [
                    json.loads(line)
                    for line in (METRICS / name).read_text(encoding="utf-8").splitlines()
                ]
                self.assertGreater(len(records), 0)
                self.assertEqual(sorted(record["at"] for record in records),
                                 [record["at"] for record in records])
                self.assertTrue(all(record.get("metrics") for record in records))
                self.assertTrue(all("error" not in record for record in records))


if __name__ == "__main__":
    unittest.main()
