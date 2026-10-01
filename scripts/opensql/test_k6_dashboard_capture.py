"""Verify that k6 sample capture strips private URL and token-bearing tags."""

import json
import unittest

from k6_dashboard_capture import safe_point


class SafePointTest(unittest.TestCase):
    """Keep only approved numeric evidence before it reaches a persisted artifact."""

    def test_discards_sensitive_tags(self):
        source = {
            "type": "Point",
            "metric": "dashboard_latency_ms",
            "data": {
                "time": "2026-10-01T15:00:00Z",
                "value": 12.5,
                "tags": {"url": "ws://private-host/ws", "Authorization": "Bearer secret"},
            },
        }

        result = safe_point(json.dumps(source).encode())

        self.assertEqual(result, {
            "시각_KST": "2026-10-02T00:00:00+09:00",
            "지표": "dashboard_latency_ms",
            "값": 12.5,
        })
        self.assertNotIn("private-host", json.dumps(result))
        self.assertNotIn("secret", json.dumps(result))

    def test_discards_unapproved_metric(self):
        source = {"type": "Point", "metric": "ws_connecting",
                  "data": {"time": "2026-10-01T15:00:00Z", "value": 1,
                           "tags": {"url": "ws://private-host/ws"}}}

        self.assertIsNone(safe_point(json.dumps(source).encode()))

    def test_discards_invalid_numeric_sample(self):
        source = {"type": "Point", "metric": "dashboard_latency_ms",
                  "data": {"time": "2026-10-01T15:00:00Z", "value": "NaN"}}

        self.assertIsNone(safe_point(json.dumps(source).encode()))


if __name__ == "__main__":
    unittest.main()
