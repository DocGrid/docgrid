#!/usr/bin/env python3
"""Verify that stale, failed and unrelated recovery guards block fault injection."""

import json
import unittest
from datetime import datetime, timedelta, timezone

from ha_vm_guard_gate import gate


class VmGuardGateTest(unittest.TestCase):
    """Keep fault authorization tied to one live execution and a future deadline."""

    def setUp(self):
        """Build a synthetic, identifier-free active execution."""
        self.now = datetime(2026, 10, 4, 0, 0, tzinfo=timezone.utc)
        self.workflow = "projects/example/locations/us-central1/workflows/guard"
        self.execution = {
            "name": self.workflow + "/executions/test-run",
            "state": "ACTIVE",
            "argument": json.dumps({"delay_seconds": 600}),
            "startTime": (self.now - timedelta(seconds=60)).isoformat(),
        }

    def test_active_guard_with_enough_time_is_armed(self):
        """A future, still-active execution permits the next preflight step."""
        self.assertEqual({"guard": "ARMED", "remaining_seconds": 540},
                         gate(self.execution, self.workflow, self.now))

    def test_failed_guard_blocks_fault(self):
        """A permission or network failure is never interpreted as an armed guard."""
        self.execution["state"] = "FAILED"
        with self.assertRaisesRegex(ValueError, "GUARD_NOT_ACTIVE"):
            gate(self.execution, self.workflow, self.now)

    def test_cancelled_guard_blocks_fault(self):
        """A cancelled or disconnected operator run cannot authorize a later fault."""
        self.execution["state"] = "CANCELLED"
        with self.assertRaisesRegex(ValueError, "GUARD_NOT_ACTIVE"):
            gate(self.execution, self.workflow, self.now)

    def test_wrong_workflow_blocks_fault(self):
        """An unrelated recovery execution cannot authorize this fault."""
        with self.assertRaisesRegex(ValueError, "WRONG_WORKFLOW"):
            gate(self.execution, self.workflow + "-other", self.now)

    def test_near_deadline_blocks_fault(self):
        """A nearly expired timer is unsafe before the fault is even injected."""
        self.execution["startTime"] = (self.now - timedelta(seconds=500)).isoformat()
        with self.assertRaisesRegex(ValueError, "GUARD_DEADLINE_TOO_CLOSE"):
            gate(self.execution, self.workflow, self.now)

    def test_missing_execution_metadata_blocks_fault(self):
        """Missing timestamp or input must not fall back to a permissive default."""
        del self.execution["argument"]
        with self.assertRaisesRegex(ValueError, "GUARD_EXECUTION_METADATA_INVALID"):
            gate(self.execution, self.workflow, self.now)


if __name__ == "__main__":
    unittest.main()
