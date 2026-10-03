"""Exercise request-level joins without a database or cloud dependency."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from analyze_ha_probe_diagnostics import analyze
from ha_evidence import EvidenceError


class DiagnosticJoinTest(unittest.TestCase):
    """Ensure 500 classifications are tied to external request IDs, not timing alone."""

    def test_joins_one_failure_and_one_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "requests.csv").write_text(
                "request_id,http_status\nha418sw00001-v1-i1,201\nha418sw00001-v1-i2,500\n",
                encoding="utf-8",
            )
            log = root / "app.jsonl"
            base = {"at": "2026-10-03T18:49:30.000Z", "run_id": "ha418sw00001",
                    "request_id": "ha418sw00001-v1-i2", "phase": "TX_BEGIN"}
            log.write_text(
                json.dumps({**base, "event": "EXCEPTION", "type": "CannotCreateTransactionException",
                            "sqlstate": "none", "status": 500}) + "\n" +
                json.dumps({**base, "event": "RESULT", "status": 500}) + "\n",
                encoding="utf-8",
            )
            with patch("analyze_ha_probe_diagnostics.verify", return_value={"run_id": "ha418sw00001"}):
                result = analyze(root, [log])

            self.assertEqual(result["client_http_500"], 1)
            self.assertEqual(result["matched_exception_500"], 1)
            self.assertEqual(result["client_500_without_app_diagnostic"], 0)

    def test_rejects_cross_run_event(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "requests.csv").write_text(
                "request_id,http_status\nha418sw00001-v1-i2,500\n", encoding="utf-8")
            log = root / "app.jsonl"
            log.write_text(json.dumps({"run_id": "ha418other", "request_id": "ha418sw00001-v1-i2",
                                       "phase": "TX_BEGIN", "event": "RESULT", "status": 500}) + "\n",
                           encoding="utf-8")
            with patch("analyze_ha_probe_diagnostics.verify", return_value={"run_id": "ha418sw00001"}):
                with self.assertRaises(EvidenceError):
                    analyze(root, [log])


if __name__ == "__main__":
    unittest.main()
