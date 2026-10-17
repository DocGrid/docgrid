#!/usr/bin/env python3
"""Check that the offline verdict uses first responses, not retry assumptions."""

import json
import tempfile
import unittest
from pathlib import Path

import audit_primary_partition as audit


class PrimaryPartitionAuditTest(unittest.TestCase):
    """Keep acknowledged loss and unknown outcomes separate in HA evidence."""

    def test_acknowledged_missing_db_is_failure(self):
        terminal = {
            "partnet01-v1-i1": {"kind": "acknowledged", "at": "2026-10-17T12:45:52.000Z"},
            "partnet01-v1-i2": {"kind": "unknown", "at": "2026-10-17T12:45:53.000Z"},
        }
        result = audit.summarize(terminal, {"partnet01-v1-i2": 1}, "2026-10-17T12:47:34.000Z")
        self.assertEqual(result["acknowledged_missing_db"], 1)
        self.assertEqual(result["unknown_in_db"], 1)
        self.assertEqual(result["verdict"], "FAIL")

    def test_duplicate_db_rows_are_failure_even_if_acknowledged(self):
        terminal = {"partnet01-v1-i1": {"kind": "acknowledged", "at": "2026-10-17T12:48:00.000Z"}}
        result = audit.summarize(terminal, {"partnet01-v1-i1": 2}, "2026-10-17T12:47:34.000Z")
        self.assertEqual(result["db_duplicate_ids"], 1)
        self.assertEqual(result["verdict"], "FAIL")

    def test_incomplete_first_response_ledger_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"
            path.write_text(json.dumps({"run_id": "partnet01", "request_id": "partnet01-v1-i1",
                                        "kind": "sent"}) + "\n")
            with self.assertRaisesRegex(ValueError, "missing_sent_or_terminal"):
                audit.read_events(path, "partnet01")


if __name__ == "__main__":
    unittest.main()
