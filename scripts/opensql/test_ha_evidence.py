"""Regression tests for the database-external OpenSQL HA evidence ledger."""

from __future__ import annotations

import csv
import concurrent.futures
import importlib.util
import json
import subprocess
import sys
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

    def test_external_run_id_matches_the_scenario_id(self):
        """A caller-supplied ID keeps the fixture, run directory, and ledger joinable."""
        other = Path(self.temporary.name) / "same-run"
        self.assertEqual(0, EVIDENCE.main([
            "init", "--run-dir", str(other), "--run-id", "scenario123",
            "--scenario", "permission-replica-lag", "--config-sha256", "b" * 64,
            "--opensql-version", "test", "--openproxy-version", "test",
            "--patroni-version", "test", "--etcd-version", "test",
        ]))
        self.assertEqual("scenario123", json.loads((other / "manifest.json").read_text())["run_id"])

    def test_bulk_k6_import_and_db_reconciliation(self):
        """One batch import preserves outcomes and detects a committed duplicate."""
        run_id = json.loads((self.directory / "manifest.json").read_text())["run_id"]
        events = [
            {"event_id": f"e-{index}", "run_id": run_id,
             "at": "2026-10-02T00:00:00.000Z", "kind": kind,
             "request_id": request_id, **fields}
            for index, (kind, request_id, fields) in enumerate([
                ("sent", f"{run_id}-one", {"operation": "ha_probe_write"}),
                ("acknowledged", f"{run_id}-one", {"http_status": 201}),
                ("sent", f"{run_id}-two", {"operation": "ha_probe_write"}),
                ("unknown", f"{run_id}-two", {"reason": "timeout"}),
            ])
        ]
        source = Path(self.temporary.name) / "k6.jsonl"
        source.write_text("".join(json.dumps(event) + "\n" for event in events))
        self.assertEqual(4, EVIDENCE.import_k6(self.directory, source))
        EVIDENCE.append_event(self.directory, "finished")
        EVIDENCE.export(self.directory)
        (self.directory / "k6-summary.json").write_text(json.dumps({
            "run_id": run_id, "iterations": 2, "http_requests": 2,
            "dropped_iterations": 0, "failed_rate": 0,
        }))
        db_csv = Path(self.temporary.name) / "db.csv"
        db_csv.write_text(f"request_id,row_count\n{run_id}-one,2\n{run_id}-two,1\n")
        command = [sys.executable, str(SCRIPT.with_name("reconcile_ha_probe.py")),
                   "--run-dir", str(self.directory), "--db-csv", str(db_csv)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        report = json.loads((self.directory / "reconciliation.json").read_text())
        self.assertEqual(1, report["acknowledged_duplicate_count"])
        self.assertEqual(1, report["unknown_persisted_count"])

    def test_bulk_k6_import_rejects_unredacted_extra_field(self):
        """Reject a raw event carrying data outside the safe allowlist before writing."""
        run_id = json.loads((self.directory / "manifest.json").read_text())["run_id"]
        source = Path(self.temporary.name) / "unsafe.jsonl"
        source.write_text(json.dumps({
            "event_id": "one", "run_id": run_id, "at": "2026-10-02T00:00:00Z",
            "kind": "sent", "request_id": "safe", "operation": "write",
            "authorization": "omitted-by-test",
        }) + "\n")
        with self.assertRaisesRegex(EVIDENCE.EvidenceError, "허용되지 않은"):
            EVIDENCE.import_k6(self.directory, source)
        self.assertEqual("", (self.directory / "events.jsonl").read_text())

    def test_one_acknowledged_row_passes_reconciliation(self):
        """A healthy baseline needs a single durable row for every HTTP success."""
        run_id = json.loads((self.directory / "manifest.json").read_text())["run_id"]
        request_id = f"{run_id}-one"
        EVIDENCE.append_event(self.directory, "sent", request_id=request_id, operation="ha_probe_write")
        EVIDENCE.append_event(self.directory, "acknowledged", request_id=request_id, http_status=201)
        EVIDENCE.append_event(self.directory, "finished")
        EVIDENCE.export(self.directory)
        (self.directory / "k6-summary.json").write_text(json.dumps({
            "run_id": run_id, "iterations": 1, "http_requests": 1,
            "dropped_iterations": 0, "failed_rate": 0,
        }))
        db_csv = Path(self.temporary.name) / "db-clean.csv"
        db_csv.write_text(f"request_id,row_count\n{request_id},1\n")
        result = subprocess.run([sys.executable, str(SCRIPT.with_name("reconcile_ha_probe.py")),
                                 "--run-dir", str(self.directory), "--db-csv", str(db_csv)],
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode)
        report = json.loads((self.directory / "reconciliation.json").read_text())
        self.assertTrue(report["normal_baseline_pass"])
        self.assertEqual(0, report["acknowledged_missing_count"])

    def test_dropped_iterations_fail_baseline_without_losing_acknowledged_data(self):
        """Issued writes can be durable while the offered-load schedule fails."""
        run_id = json.loads((self.directory / "manifest.json").read_text())["run_id"]
        request_id = f"{run_id}-one"
        EVIDENCE.append_event(self.directory, "sent", request_id=request_id, operation="ha_probe_write")
        EVIDENCE.append_event(self.directory, "acknowledged", request_id=request_id, http_status=201)
        EVIDENCE.append_event(self.directory, "finished")
        EVIDENCE.export(self.directory)
        (self.directory / "k6-summary.json").write_text(json.dumps({
            "run_id": run_id, "iterations": 1, "http_requests": 1,
            "dropped_iterations": 2, "failed_rate": 0,
        }))
        db_csv = Path(self.temporary.name) / "db-dropped.csv"
        db_csv.write_text(f"request_id,row_count\n{request_id},1\n")
        result = subprocess.run([sys.executable, str(SCRIPT.with_name("reconcile_ha_probe.py")),
                                 "--run-dir", str(self.directory), "--db-csv", str(db_csv)],
                                capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        report = json.loads((self.directory / "reconciliation.json").read_text())
        self.assertTrue(report["durable_write_pass"])
        self.assertFalse(report["load_schedule_pass"])
        self.assertFalse(report["normal_baseline_pass"])


if __name__ == "__main__":
    unittest.main()
