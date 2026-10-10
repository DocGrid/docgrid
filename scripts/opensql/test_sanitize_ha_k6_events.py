"""Check that only the allowlisted k6 probe event schema can leave the load VM."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).with_name("sanitize_ha_k6_events.py")
SPEC = importlib.util.spec_from_file_location("sanitize_ha_k6_events", SOURCE)
SANITIZER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SANITIZER)


class SanitizedHaK6EventsTest(unittest.TestCase):
    """Prove that valid probe records pass and unexpected data blocks the whole export."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.source = Path(self.temporary.name) / "events.jsonl"
        self.output = Path(self.temporary.name) / "safe.jsonl"
        self.sent = {
            "at": "2026-10-02T11:40:48.123Z", "event_id": "ha391b100-v1-i0-sent",
            "kind": "sent", "operation": "ha_probe_write",
            "request_id": "ha391b100-v1-i0", "run_id": "ha391b100",
        }

    def write(self, *events):
        self.source.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")

    def test_valid_pair_preserves_only_safe_fields(self):
        acknowledged = {
            "at": "2026-10-02T11:40:48.234Z", "event_id": "ha391b100-v1-i0-acknowledged",
            "kind": "acknowledged", "http_status": 201,
            "request_id": "ha391b100-v1-i0", "run_id": "ha391b100",
        }
        self.write(self.sent, acknowledged)
        self.assertEqual(2, SANITIZER.sanitize(self.source, self.output, "ha391b100"))
        self.assertEqual([self.sent, acknowledged],
                         [json.loads(line) for line in self.output.read_text().splitlines()])

    def test_idempotent_probe_operation_is_allowlisted(self):
        self.write({**self.sent, "operation": "ha_probe_idempotent_write"})
        self.assertEqual(1, SANITIZER.sanitize(self.source, self.output, "ha391b100"))

    def test_extra_url_rejects_entire_export(self):
        leaked = {**self.sent, "target_url": "http://private.invalid"}
        self.write(self.sent, leaked)
        with self.assertRaises(ValueError):
            SANITIZER.sanitize(self.source, self.output, "ha391b100")
        self.assertFalse(self.output.exists())

    def test_wrong_status_rejects_export(self):
        failed = {**self.sent, "kind": "acknowledged", "http_status": 500,
                  "event_id": "ha391b100-v1-i0-acknowledged"}
        failed.pop("operation")
        self.write(failed)
        with self.assertRaises(ValueError):
            SANITIZER.sanitize(self.source, self.output, "ha391b100")
        self.assertFalse(self.output.exists())

    def test_unexpected_http_200_is_safe_failed_evidence(self):
        """An HTTP response can be 2xx while violating the probe's 201 contract."""
        failed = {
            "at": "2026-10-02T11:40:48.234Z", "event_id": "ha391b100-v1-i0-failed",
            "kind": "failed", "http_status": 200,
            "request_id": "ha391b100-v1-i0", "run_id": "ha391b100",
        }
        self.write(self.sent, failed)
        self.assertEqual(2, SANITIZER.sanitize(self.source, self.output, "ha391b100"))

    def test_failed_201_is_rejected(self):
        """The only accepted 201 classification is acknowledged."""
        failed = {
            "at": "2026-10-02T11:40:48.234Z", "event_id": "ha391b100-v1-i0-failed",
            "kind": "failed", "http_status": 201,
            "request_id": "ha391b100-v1-i0", "run_id": "ha391b100",
        }
        self.write(self.sent, failed)
        with self.assertRaises(ValueError):
            SANITIZER.sanitize(self.source, self.output, "ha391b100")


if __name__ == "__main__":
    unittest.main()
