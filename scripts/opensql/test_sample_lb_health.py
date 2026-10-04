"""Verify that LB health collection retains only backend state counts."""

import json
import unittest

from sample_lb_health import health_counts


class HealthCountsTest(unittest.TestCase):
    """Guard the numeric-only boundary before reading cloud health responses."""

    def test_counts_only_states(self):
        source = [
            {"backend": "private-backend-a", "status": {
                "healthStatus": [{"healthState": "HEALTHY", "ipAddress": "private-a"}]}},
            {"backend": "private-backend-b", "status": {
                "healthStatus": [{"healthState": "UNHEALTHY", "ipAddress": "private-b"}]}},
        ]
        self.assertEqual((1, 1, 0), health_counts(json.dumps(source)))

    def test_rejects_missing_states(self):
        with self.assertRaises(ValueError):
            health_counts('[{"status":{"healthStatus":[]}}]')


if __name__ == "__main__":
    unittest.main()
