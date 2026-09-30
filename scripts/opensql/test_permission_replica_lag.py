"""Regression checks for permission-lag evidence classification and safety gates."""

from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("permission_replica_lag.py")
SPEC = importlib.util.spec_from_file_location("permission_replica_lag", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Permission experiment module cannot be loaded")
EXPERIMENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPERIMENT)


class PermissionReplicaLagTest(unittest.TestCase):
    """Keep a denied HTTP response distinct from a proven standby denial."""

    def test_403_without_standby_query_is_invalid(self):
        """An authentication failure or primary read must not appear as a safe denial."""
        no_query = dict.fromkeys(EXPERIMENT.NODES, 0)
        self.assertEqual("INVALID", EXPERIMENT.classify_result(403, False, no_query, 403, 403))
        primary_query = no_query | {EXPERIMENT.NODES[0]: 1}
        self.assertEqual("INVALID", EXPERIMENT.classify_result(403, False, primary_query, 403, 403))

    def test_standby_read_separates_stale_and_denied(self):
        """Both outcomes require a real standby lookup and two 403 controls."""
        standby_query = {EXPERIMENT.NODES[0]: 0, EXPERIMENT.NODES[1]: 1,
                         EXPERIMENT.NODES[2]: 0}
        self.assertEqual("STALE_ADMIN_ALLOWED", EXPERIMENT.classify_result(
            200, True, standby_query, 403, 403))
        self.assertEqual("DENIED", EXPERIMENT.classify_result(
            403, False, standby_query, 403, 403))
        self.assertEqual("INVALID", EXPERIMENT.classify_result(
            403, True, standby_query, 403, 403))
        self.assertEqual("INVALID", EXPERIMENT.classify_result(
            200, True, standby_query, 200, 403))

    def test_standby_status_requires_both_tag_and_timer(self):
        """A delay alone cannot authorize a revocation while replicas remain promotable."""
        delayed = ("standby=true streaming=true armed=true delay=120000,configuration file "
                   "nofailover=true nofailover_cleared=false backlog_bytes=456 free_kb=2000000")
        both = dict.fromkeys(EXPERIMENT.STANDBYS, delayed)
        self.assertTrue(EXPERIMENT.delayed_status_is_safe(both))
        self.assertFalse(EXPERIMENT.delayed_status_is_safe(
            both | {EXPERIMENT.NODES[1]: delayed.replace("nofailover=true", "nofailover=false")}))
        self.assertFalse(EXPERIMENT.delayed_status_is_safe({EXPERIMENT.NODES[1]: delayed}))

    def test_restored_status_requires_promotion_eligibility(self):
        """Cleanup is not complete while either standby remains excluded or delayed."""
        restored = ("standby=true streaming=true armed=false delay=0,default "
                    "nofailover=false nofailover_cleared=true backlog_bytes=0 free_kb=2000000")
        both = dict.fromkeys(EXPERIMENT.STANDBYS, restored)
        self.assertTrue(EXPERIMENT.restored_status_is_safe(both))
        self.assertFalse(EXPERIMENT.restored_status_is_safe(
            both | {EXPERIMENT.NODES[2]: restored.replace("nofailover_cleared=true",
                                                         "nofailover_cleared=false")}))


if __name__ == "__main__":
    unittest.main()
