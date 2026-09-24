"""Regression tests for the database-external OpenSQL HA evidence ledger."""

from __future__ import annotations

import csv
import concurrent.futures
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("ha_evidence.py")
SPEC = importlib.util.spec_from_file_location("ha_evidence", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("증거 기록기를 읽을 수 없습니다")
EVIDENCE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVIDENCE)


class HaEvidenceTest(unittest.TestCase):
    """Keep success, failure, and unknown outcomes distinct across ledger replay."""

    def setUp(self):
        """Create an isolated run with a non-secret configuration fingerprint."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name) / "run"
        self.assertEqual(0, EVIDENCE.main([
            "init", "--run-dir", str(self.directory), "--scenario", "proxy-failover",
            "--config-sha256", "a" * 64,
            "--opensql-version", "test-open-sql", "--openproxy-version", "test-proxy",
            "--patroni-version", "test-patroni", "--etcd-version", "test-etcd",
        ]))

    def test_three_outcomes_fault_timeline_and_export_agree(self):
        """Only a confirmed 2xx response is acknowledged; missing terminals stay unknown."""
        EVIDENCE.append_event(self.directory, "sent", request_id="write-1", operation="create-document")
        EVIDENCE.append_event(self.directory, "acknowledged", request_id="write-1", http_status=201)
        EVIDENCE.append_event(self.directory, "fault", name="proxy-a-stop", phase="start")
        EVIDENCE.append_event(self.directory, "sent", request_id="write-2", operation="create-document")
        EVIDENCE.append_event(self.directory, "failed", request_id="write-2", http_status=503)
        EVIDENCE.append_event(self.directory, "sent", request_id="write-3", operation="create-document")
        EVIDENCE.append_event(self.directory, "unknown", request_id="write-3", reason="timeout")
        EVIDENCE.append_event(self.directory, "sent", request_id="write-4", operation="create-document")
        EVIDENCE.append_event(self.directory, "fault", name="proxy-a-stop", phase="end")
        EVIDENCE.append_event(self.directory, "finished")

        summary = EVIDENCE.export(self.directory)
        self.assertEqual({"ACKNOWLEDGED": 1, "FAILED": 1, "UNKNOWN": 2}, summary["outcome_counts"])
        self.assertEqual(4, summary["request_count"])
        self.assertEqual(2, len(summary["fault_events"]))
        self.assertTrue(summary["complete"])
        self.assertEqual(summary, EVIDENCE.verify(self.directory))
        with (self.directory / "requests.csv").open(newline="", encoding="utf-8") as input_file:
            rows = list(csv.DictReader(input_file))
        self.assertEqual("no_terminal_event", rows[-1]["reason"])
        self.assertEqual("UNKNOWN", rows[-1]["outcome"])
        manifest = json.loads((self.directory / "manifest.json").read_text())
        self.assertEqual("a" * 64, manifest["config_sha256"])
        self.assertEqual(40, len(manifest["git_sha"]))

    def test_duplicate_and_invalid_terminals_are_not_appended(self):
        """A malformed result cannot silently overwrite the original request outcome."""
        EVIDENCE.append_event(self.directory, "sent", request_id="one", operation="write")
        ledger = self.directory / "events.jsonl"
        before = ledger.read_bytes()
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "2xx"):
            EVIDENCE.append_event(self.directory, "acknowledged", request_id="one", http_status=503)
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "미완료"):
            EVIDENCE.append_event(self.directory, "failed", request_id="missing", http_status=503)
        self.assertEqual(before, ledger.read_bytes())
        EVIDENCE.append_event(self.directory, "acknowledged", request_id="one", http_status=200)
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "미완료"):
            EVIDENCE.append_event(self.directory, "failed", request_id="one", http_status=500)

    def test_tampered_raw_or_derived_evidence_fails_verification(self):
        """The report is not trusted when a raw line or derived count is edited."""
        EVIDENCE.append_event(self.directory, "sent", request_id="one", operation="write")
        EVIDENCE.export(self.directory)
        summary_path = self.directory / "summary.json"
        summary_path.write_text(summary_path.read_text().replace('"UNKNOWN": 1', '"UNKNOWN": 0'))
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "summary.json"):
            EVIDENCE.verify(self.directory)
        EVIDENCE.export(self.directory)
        ledger = self.directory / "events.jsonl"
        ledger.write_text(ledger.read_text() + "{not-json}\n")
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "손상"):
            EVIDENCE.verify(self.directory)

    def test_unclosed_fault_and_post_finish_event_are_visible_or_rejected(self):
        """An unfinished fault cannot be reported as a complete experiment."""
        EVIDENCE.append_event(self.directory, "fault", name="leader-stop", phase="start")
        EVIDENCE.append_event(self.directory, "finished")
        summary = EVIDENCE.export(self.directory)
        self.assertFalse(summary["complete"])
        self.assertEqual(["leader-stop"], summary["open_faults"])
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "종료 뒤"):
            EVIDENCE.append_event(self.directory, "sent", request_id="late", operation="write")

    def test_concurrent_requests_and_csv_labels_remain_safe(self):
        """Concurrent writers keep every line, while spreadsheet formulas are rejected."""
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda index: EVIDENCE.append_event(
                self.directory, "sent", request_id=f"request-{index}", operation="write"
            ), range(30)))
        summary = EVIDENCE.export(self.directory)
        self.assertEqual(30, summary["outcome_counts"]["UNKNOWN"])
        self.assertEqual(30, len((self.directory / "events.jsonl").read_text().splitlines()))
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "operation"):
            EVIDENCE.append_event(self.directory, "sent", request_id="bad", operation="=HYPERLINK")

    def test_invalid_run_metadata_is_rejected_before_directory_creation(self):
        """A malformed fingerprint cannot leave a misleading partial run."""
        other = Path(self.temporary.name) / "invalid"
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "SHA-256"):
            EVIDENCE.initialize(type("Args", (), {
                "run_dir": other, "scenario": "proxy-stop", "config_sha256": "bad",
                "opensql_version": "17.8", "openproxy_version": "1",
                "patroni_version": "4", "etcd_version": "3",
            })())
        self.assertFalse(other.exists())


if __name__ == "__main__":
    unittest.main()
