"""Regression tests for the HA dashboard's safe final-count verdict."""

from __future__ import annotations

import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import verify_ha_prometheus_rw as remote_write


class HaPrometheusRemoteWriteTest(unittest.TestCase):
    """Keep dashboard totals separate from live delivery and DB reconciliation."""

    def setUp(self):
        """Create one private event ledger for each test case."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.events = Path(temporary.name) / "events.jsonl"
        self.run_id = "ha-demo-001"

    def write_event(self, request_id: str, kind: str, **fields):
        """Write only the same allowlisted fields the k6 script emits."""
        item = {
            "at": "2026-10-09T00:00:00.000Z",
            "event_id": f"{request_id}-{kind}",
            "kind": kind,
            "request_id": request_id,
            "run_id": self.run_id,
            **fields,
        }
        with self.events.open("a", encoding="utf-8") as output:
            output.write(json.dumps(item) + "\n")

    def test_counts_all_terminal_buckets_without_collapsing_other_failures(self):
        """A 502 remains visible when the dashboard highlights 500/503."""
        cases = (
            ("acknowledged", {"http_status": 201}),
            ("failed", {"http_status": 500}),
            ("failed", {"http_status": 503}),
            ("failed", {"http_status": 502}),
            ("unknown", {"reason": "other"}),
        )
        for index, (kind, fields) in enumerate(cases):
            request_id = f"{self.run_id}-v1-i{index}"
            self.write_event(request_id, "sent", operation="ha_probe_write")
            self.write_event(request_id, kind, **fields)
        self.assertEqual({name: 1 for name in remote_write.OUTCOMES},
                         remote_write.expected_outcomes(self.events, self.run_id))

    def test_rejects_missing_or_duplicate_terminal_events(self):
        """An incomplete client ledger cannot validate remote-write delivery."""
        request_id = f"{self.run_id}-v1-i0"
        self.write_event(request_id, "sent", operation="ha_probe_write")
        with self.assertRaisesRegex(ValueError, "빠졌습니다"):
            remote_write.expected_outcomes(self.events, self.run_id)
        self.write_event(request_id, "unknown", reason="other")
        self.write_event(request_id, "unknown", reason="other")
        with self.assertRaisesRegex(ValueError, "중복 종료"):
            remote_write.expected_outcomes(self.events, self.run_id)

    def test_accepts_only_private_ipv4_receiver_without_credentials(self):
        """A mistake cannot send run metrics to a public or credentialed URL."""
        self.assertEqual("http://10.1.2.3:9090/api/v1/query",
                         remote_write.query_base("http://10.1.2.3:9090/api/v1/write"))
        for url in (
            "http://8.8.8.8:9090/api/v1/write",
            "http://127.0.0.1:9090/api/v1/write",
            "http://user:secret@10.1.2.3:9090/api/v1/write",
            "http://10.1.2.3:9090/api/v1/write?token=secret",
            "https://10.1.2.3:9090/api/v1/write",
        ):
            with self.subTest(url=url), self.assertRaises(ValueError):
                remote_write.query_base(url)

    def test_final_remote_count_uses_one_safe_prometheus_query(self):
        """Read the final non-stale Counter without a network endpoint."""
        payload = {"status": "success", "data": {"resultType": "vector", "result": [
            {"metric": {"run_id": self.run_id}, "value": [1, "3"]}
        ]}}

        class FakeOpener:
            """Return an in-memory response and preserve the requested URL."""

            def open(self, request, timeout):
                """Record the query without sending data to a network endpoint."""
                self.url = request.full_url
                return io.BytesIO(json.dumps(payload).encode())

        opener = FakeOpener()
        with patch.object(remote_write, "build_opener", return_value=opener):
            count = remote_write.remote_count(
                "http://10.1.2.3:9090/api/v1/query", self.run_id, "201")
        self.assertEqual(3, count)
        self.assertIn("k6_ha_outcome_201_total", opener.url)
        self.assertIn("last_over_time", opener.url)

    def test_verdict_explicitly_excludes_live_gaps_and_database_effects(self):
        """Matching totals do not claim a continuous graph or RPO 0."""
        request_id = f"{self.run_id}-v1-i0"
        self.write_event(request_id, "sent", operation="ha_probe_write")
        self.write_event(request_id, "acknowledged", http_status=201)
        with patch.object(remote_write, "remote_count", side_effect=lambda _, __, name:
                          1 if name == "201" else 0):
            result = remote_write.verify(
                self.events, self.run_id, "http://10.1.2.3:9090/api/v1/write")
        self.assertTrue(result["final_totals_match"])
        self.assertEqual(1, result["query_attempts"])
        self.assertFalse(result["intermediate_delivery_verified"])
        self.assertFalse(result["db_reconciliation_verified"])

    def test_missing_final_counter_fails_after_bounded_ingestion_wait(self):
        """A broken remote output must not be labeled a successful run."""
        request_id = f"{self.run_id}-v1-i0"
        self.write_event(request_id, "sent", operation="ha_probe_write")
        self.write_event(request_id, "acknowledged", http_status=201)
        with patch.object(remote_write, "remote_count", return_value=0), \
             patch.object(remote_write.time, "sleep") as sleep:
            result = remote_write.verify(
                self.events, self.run_id, "http://10.1.2.3:9090/api/v1/write")
        self.assertFalse(result["final_totals_match"])
        self.assertEqual(4, result["query_attempts"])
        self.assertEqual(3, sleep.call_count)


if __name__ == "__main__":
    unittest.main()
