"""Deterministically test three-VM fixture cleanup without touching GCP."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("ha_fixture_cleanup.py")
SPEC = importlib.util.spec_from_file_location("ha_fixture_cleanup", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Fixture cleanup module cannot be loaded")
GUARD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GUARD)
RUN_ID = "abc123def456"


class FakeCluster:
    """Model local roles, timers, and replicated fixture counts for the runner."""

    def __init__(self):
        self.roles = dict(zip(GUARD.NODES, ("primary", "replica", "replica")))
        self.armed = dict.fromkeys(GUARD.NODES, False)
        self.counts = dict.fromkeys(GUARD.NODES, 0)
        self.unavailable = set()
        self.fail_arm = set()
        self.fail_remove = False
        self.calls = []

    def remote(self, node, script, action, container, run_id, seconds):
        self.calls.append((node, action))
        if node in self.unavailable or node != container or script != GUARD.HELPER:
            raise GUARD.InvalidExperiment("Remote guard unavailable")
        if action == "role":
            return self.roles[node]
        if action == "stale-count":
            return "0"
        if action == "guard-status":
            return f"armed={str(self.armed[node]).lower()} fixture_count={self.counts[node]}"
        if action == "arm":
            if node in self.fail_arm:
                raise GUARD.InvalidExperiment("Timer unavailable")
            self.armed[node] = True
            return "fixture-armed"
        if action == "create":
            if self.roles[node] != "primary" or not all(self.armed.values()):
                raise AssertionError("Create occurred without a primary and all three timers")
            for name in self.counts:
                self.counts[name] = 4
            return "\n".join(f"ha-{run_id}-{alias},{index}" for index, alias in enumerate(
                ("admin", "m", "c1", "c2"), start=1))
        if action == "remove":
            if self.fail_remove or self.roles[node] != "primary":
                raise GUARD.InvalidExperiment("Removal unavailable")
            for name in self.counts:
                self.counts[name] = 0
            return "fixture-removed"
        if action == "cancel":
            if self.counts[node] != 0:
                raise AssertionError("Timer cancelled before fixture deletion")
            self.armed[node] = False
            return "fixture-cancelled"
        raise AssertionError(f"Unexpected action: {action}")


class HaFixtureCleanupTest(unittest.TestCase):
    """Require independent guards and protect cleanup during one VM loss."""

    def setUp(self):
        self.cluster = FakeCluster()
        patcher = patch.object(GUARD, "remote", side_effect=self.cluster.remote)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_prepare_arms_all_three_before_creating(self):
        GUARD.prepare(RUN_ID, 900)
        self.assertEqual(3, sum(self.cluster.armed.values()))
        self.assertEqual(4, self.cluster.counts[GUARD.NODES[0]])
        create_index = self.cluster.calls.index((GUARD.NODES[0], "create"))
        for node in GUARD.NODES:
            self.assertLess(self.cluster.calls.index((node, "arm")), create_index)
            self.assertLess(self.cluster.calls.index((node, "guard-status")), create_index)

    def test_failed_arm_prevents_create_and_cancels_prior_timer(self):
        self.cluster.fail_arm.add(GUARD.NODES[1])
        with self.assertRaises(GUARD.InvalidExperiment):
            GUARD.prepare(RUN_ID, 900)
        self.assertFalse(any(self.cluster.armed.values()))
        self.assertFalse(any(action == "create" for _, action in self.cluster.calls))

    def test_cleanup_after_node1_loss_uses_promoted_node2_and_retains_unreachable_guard(self):
        GUARD.prepare(RUN_ID, 900)
        self.cluster.roles[GUARD.NODES[0]] = "replica"
        self.cluster.roles[GUARD.NODES[1]] = "primary"
        self.cluster.unavailable.add(GUARD.NODES[0])
        with self.assertRaisesRegex(GUARD.InvalidExperiment, "still needs audit"):
            GUARD.cleanup(RUN_ID, 900)
        self.assertIn((GUARD.NODES[1], "remove"), self.cluster.calls)
        self.assertEqual(0, self.cluster.counts[GUARD.NODES[1]])
        self.assertTrue(self.cluster.armed[GUARD.NODES[0]])
        self.assertFalse(self.cluster.armed[GUARD.NODES[1]])
        self.assertFalse(self.cluster.armed[GUARD.NODES[2]])

    def test_cleanup_failure_never_cancels_timer(self):
        GUARD.prepare(RUN_ID, 900)
        self.cluster.fail_remove = True
        with self.assertRaises(GUARD.InvalidExperiment):
            GUARD.cleanup(RUN_ID, 900)
        self.assertEqual(3, sum(self.cluster.armed.values()))
        self.assertFalse(any(action == "cancel" for _, action in self.cluster.calls))

    def test_no_observed_primary_stops_cleanup_without_a_write(self):
        GUARD.prepare(RUN_ID, 900)
        self.cluster.roles = dict.fromkeys(GUARD.NODES, "replica")
        self.cluster.calls.clear()
        with self.assertRaises(GUARD.InvalidExperiment):
            GUARD.cleanup(RUN_ID, 900)
        self.assertFalse(any(action in {"remove", "cancel"} for _, action in self.cluster.calls))
        self.assertEqual(3, sum(self.cluster.armed.values()))

    def test_multiple_primaries_stop_before_fixture_write(self):
        self.cluster.roles[GUARD.NODES[1]] = "primary"
        with self.assertRaises(GUARD.InvalidExperiment):
            GUARD.prepare(RUN_ID, 900)
        self.assertFalse(any(action in {"arm", "create"} for _, action in self.cluster.calls))

    def test_cleanup_disarms_all_after_zero_users(self):
        GUARD.prepare(RUN_ID, 900)
        GUARD.cleanup(RUN_ID, 900)
        self.assertFalse(any(self.cluster.armed.values()))
        self.assertFalse(any(self.cluster.counts.values()))


if __name__ == "__main__":
    unittest.main()
