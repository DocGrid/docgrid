"""Unit tests for deterministic observability drill parsing and negative assertions."""

import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("run_drill.py")
SPEC = importlib.util.spec_from_file_location("docgrid_run_drill", MODULE_PATH)
run_drill = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(run_drill)


class CircuitDrillHelpersTest(unittest.TestCase):
    """Verify the Circuit scenario reads exact labels and enforces absent-event windows."""

    def test_circuit_metric_snapshot_reads_all_required_series(self):
        metrics = """
docgrid_embedding_provider_circuit_open 1.0
docgrid_embedding_provider_circuit_probe_failed 1.0
docgrid_embedding_provider_circuit_transitions_total{state="open"} 2.0
docgrid_embedding_provider_circuit_transitions_total{state="half_open"} 1.0
docgrid_embedding_provider_circuit_transitions_total{state="closed"} 0.0
docgrid_embedding_provider_circuit_probe_total{outcome="failed"} 1.0
docgrid_embedding_provider_circuit_probe_total{outcome="success"} 0.0
docgrid_embedding_delayed_retry_jobs 1.0
""".strip()

        with patch.object(run_drill, "fetch_metrics", return_value=metrics):
            snapshot = run_drill.circuit_metric_snapshot({"metricsUrl": "unused"})

        self.assertEqual({
            "open": 1.0,
            "probeFailed": 1.0,
            "openTransitions": 2.0,
            "halfOpenTransitions": 1.0,
            "closedTransitions": 0.0,
            "failedProbes": 1.0,
            "successfulProbes": 0.0,
            "delayedRetryJobs": 1.0,
        }, snapshot)

    def test_assert_absent_for_returns_after_clean_window(self):
        run_drill.assert_absent_for(
            "clean webhook window",
            lambda: False,
            duration_seconds=0.005,
            interval_seconds=0.001,
        )

    def test_assert_absent_for_fails_when_event_appears(self):
        with self.assertRaisesRegex(AssertionError, "Unexpected event"):
            run_drill.assert_absent_for(
                "unexpected webhook",
                lambda: True,
                duration_seconds=1,
                interval_seconds=0.001,
            )


if __name__ == "__main__":
    unittest.main()
