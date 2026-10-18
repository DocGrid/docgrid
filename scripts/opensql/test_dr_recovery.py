#!/usr/bin/env python3
"""Contract tests use synthetic JSON and local child commands, never GCP or a DB."""
import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import sys
import tempfile
import unittest

import dr_recovery as dr


class RecoveryContractTest(unittest.TestCase):
    """Keep first-restoration evidence immutable before any simulated Worker activation."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.run = self.root / "run"
        dr.initialize(self.run, "DR-CONTRACT-01", "4cfe966")
        self.inputs = {"schema_version": 1, "captured_at_utc": dr.utc_now(),
            "checks": {name: True for name in dr.CHECKS},
            "backup": {"id": "20261018T010000", "sha256": "a" * 64, "size": 100, "generation": "1"},
            "identity_sha256": "b" * 64,
            "wals": [{"name": "000000010000000000000001", "sha256": "c" * 64,
                      "size": 16777216, "generation": "2"}],
            "originals": [{"fileObjectId": 3, "sha256": "d" * 64, "size": 33, "generation": "7"}]}

    def sent(self, key="r1", cohort="functional", status=201):
        record = {"request_id": key, "cohort": cohort, "sha256": "d" * 64, "size": 33}
        dr.record_sent(self.run, record)
        ids = dict(zip(dr.IDS, (1, 2, 3, 4)))
        dr.record_response(self.run, {"request_id": key, "http_status": status,
                                    **({"ids": ids} if status in (200, 201) else {})})
        return {**record, "http_status": status,
                "outcome": "ACKNOWLEDGED" if status in (200, 201) else "UNKNOWN" if status is None else "HTTP_ERROR",
                **({"ids": ids} if status in (200, 201) else {})}

    def snapshot(self, states=None):
        rows = []
        for r in dr.requests(self.run):
            state = (states or {}).get(r["request_id"], "RESTORED")
            rows.append({"request_id": r["request_id"], "state": state,
                         "ids": dict(zip(dr.IDS, (1, 2, 3, 4))), "relations_match": True,
                         "sha256": r["sha256"], "size": r["size"], "generation": "7"})
        return {"worker_started": False, "flyway_ran": False, "repair_ran": False,
                "source_access_used": False, "records": rows}

    def plan(self, snapshot=None, fail=None):
        snapshot = snapshot or self.snapshot()
        receipts = {"restore": {"restored": True, "source_access_used": False, "inputs_sha256": dr.digest(self.inputs)},
                    "snapshot": snapshot, "activate": {"activated": True},
                    "cleanup": {"public_config_preserved": True, "source_cluster_restored": True,
                                "owned_resources_removed": True}}
        final = self.snapshot()
        final["worker_started"] = True
        final["consistency"] = {"checked_documents": 2, "incomplete_jobs": 0,
                                "duplicate_chunks": 0, "duplicate_embeddings": 0, "missing_embeddings": 0}
        receipts["final_snapshot"] = final
        fixture = self.root / "receipts.json"
        fixture.write_text(json.dumps(receipts))
        script = self.root / "controlled_driver.py"
        script.write_text('''import datetime,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); phase=sys.argv[2]
with (root/'calls.txt').open('a') as f:f.write(phase+'\\n')
if phase==sys.argv[3]:raise SystemExit(1)
if phase=='activate':
 assert (root/'run'/'initial-audit.json').exists()
 assert json.loads((root/'run'/'initial-audit.json').read_text())['activation_allowed']
if phase=='functional':
 print(json.dumps({name:{'passed':True,'samples':1,'at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()} for name in ['download','search','job_resume','new_upload','new_indexing','new_download']}))
else:print(json.dumps(json.loads((root/'receipts.json').read_text())[phase]))
''')
        return {name: {"argv": [sys.executable, str(script), str(self.root), name, fail or "none"],
                       "timeout_seconds": 5} for name in
                ("restore", "snapshot", "activate", "functional", "final_snapshot", "cleanup")}

    def ready(self):
        dr.freeze_inputs(self.run, self.inputs)

    def test_inputs_missing_check_is_no_go(self):
        for name in dr.CHECKS:
            with self.subTest(name=name):
                value = copy.deepcopy(self.inputs); value["checks"][name] = False
                self.assertFalse(dr.validate_inputs(value)["ready"])

    def test_partial_wal_rejected(self):
        self.inputs["wals"][0]["name"] += ".partial"
        with self.assertRaisesRegex(dr.ContractError, "INVALID_WAL_OBJECT"):
            dr.validate_inputs(self.inputs)

    def test_duplicate_wal_rejected(self):
        self.inputs["wals"] *= 2
        with self.assertRaisesRegex(dr.ContractError, "INVALID_WAL_OBJECT"):
            dr.validate_inputs(self.inputs)

    def test_original_identity_must_be_preserved(self):
        self.inputs.pop("identity_sha256")
        with self.assertRaisesRegex(dr.ContractError, "NO_OFFSITE_IDENTITY"):
            self.ready()

    def test_frozen_inputs_cannot_be_replaced(self):
        self.ready()
        with self.assertRaisesRegex(dr.ContractError, "INPUTS_ALREADY_FROZEN"):
            self.ready()

    def test_changed_inputs_block_operator(self):
        self.sent(); self.ready()
        (self.run / "inputs.json").write_text('{}')
        with self.assertRaisesRegex(dr.ContractError, "FROZEN_INPUTS_CHANGED"):
            dr.execute(self.run, self.plan())
        self.assertFalse((self.root / "calls.txt").exists())

    def test_response_ledger_is_durable_and_private(self):
        self.sent()
        self.assertEqual(len(dr.events(self.run)), 2)
        self.assertEqual((self.run / "events.jsonl").stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.run.stat().st_mode & 0o777, 0o700)

    def test_corrupt_ledger_is_not_summarized(self):
        self.sent()
        p = self.run / "events.jsonl"; p.write_text(p.read_text().replace('"size": 33', '"size": 34'))
        with self.assertRaisesRegex(dr.ContractError, "CORRUPT_EVENT_LEDGER"):
            dr.requests(self.run)

    def test_duplicate_response_rejected(self):
        self.sent()
        with self.assertRaisesRegex(dr.ContractError, "DUPLICATE_RESPONSE"):
            dr.record_response(self.run, {"request_id": "r1", "http_status": None})

    def test_success_requires_all_four_ids(self):
        dr.record_sent(self.run, {"request_id": "r1", "cohort": "loss", "sha256": "d" * 64, "size": 33})
        with self.assertRaisesRegex(dr.ContractError, "SUCCESS_WITHOUT_IDS"):
            dr.record_response(self.run, {"request_id": "r1", "http_status": 201, "ids": {"documentId": 1}})

    def test_http_error_may_be_present_in_restored_db(self):
        self.sent(status=500)
        result = dr.audit_records(dr.requests(self.run), self.snapshot())
        self.assertEqual(result["http_error"], 1)
        self.assertEqual(result["acknowledged"], 0)
        self.assertEqual(result["requests"][0]["restored_state"], "RESTORED")

    def test_missing_response_is_unknown_not_failure(self):
        dr.record_sent(self.run, {"request_id": "r1", "cohort": "functional", "sha256": "d" * 64, "size": 33})
        self.assertEqual(dr.requests(self.run)[0]["outcome"], "UNKNOWN")

    def test_success_response_ids_must_match_restored_relations(self):
        self.sent(); s = self.snapshot(); s["records"][0]["ids"]["embeddingJobId"] = 99
        with self.assertRaisesRegex(dr.ContractError, "RESPONSE_BINDING_MISMATCH"):
            dr.audit_records(dr.requests(self.run), s)

    def test_old_and_new_success_do_not_hide_middle_loss(self):
        self.sent("r1"); self.sent("r2", "loss"); self.sent("r3", "loss")
        result = dr.audit_records(dr.requests(self.run), self.snapshot({"r2": "DB_MISSING"}))
        self.assertEqual(result["acknowledged"], 3)
        self.assertEqual(result["lost_or_incomplete_acknowledged"], 1)
        self.assertTrue(result["activation_allowed"])

    def test_private_extra_snapshot_fields_are_rejected_before_recording(self):
        self.sent(); snapshot = self.snapshot(); snapshot['credential'] = 'not-a-real-secret'
        with self.assertRaisesRegex(dr.ContractError, "UNSAFE_SNAPSHOT_FIELDS"):
            dr.audit_records(dr.requests(self.run), snapshot)

    def test_private_extra_row_fields_are_rejected_before_recording(self):
        self.sent(); snapshot = self.snapshot(); snapshot['records'][0]['endpoint'] = 'private-omitted'
        with self.assertRaisesRegex(dr.ContractError, "UNSAFE_SNAPSHOT_FIELDS"):
            dr.audit_records(dr.requests(self.run), snapshot)

    def test_restore_receipt_must_bind_frozen_manifest(self):
        self.sent(); self.ready(); plan = self.plan()
        p = self.root/'receipts.json'; receipts = json.loads(p.read_text())
        receipts['restore']['inputs_sha256'] = '0' * 64; p.write_text(json.dumps(receipts))
        result = dr.execute(self.run, plan)
        self.assertEqual(result['reason'], 'RESTORE_SOURCE_DEPENDENCY')
        self.assertEqual((self.root/'calls.txt').read_text().splitlines(), ['restore', 'cleanup'])

    def test_missing_pinned_originals_is_no_go(self):
        self.inputs.pop('originals')
        with self.assertRaisesRegex(dr.ContractError, 'NO_PINNED_ORIGINALS'):
            self.ready()

    def test_wrong_preserved_generation_blocks_activation(self):
        self.sent(); self.ready(); snapshot = self.snapshot()
        snapshot['records'][0]['generation'] = '8'
        result = dr.execute(self.run, self.plan(snapshot))
        self.assertEqual(result['reason'], 'RESTORED_OBJECT_NOT_PINNED')
        self.assertNotIn('activate', (self.root/'calls.txt').read_text().splitlines())

    def test_private_consistency_field_is_rejected(self):
        self.sent(); snapshot = self.snapshot()
        snapshot['consistency'] = {'credential': 'synthetic-omission'}
        with self.assertRaisesRegex(dr.ContractError, 'UNSAFE_SNAPSHOT_FIELDS'):
            dr.audit_records(dr.requests(self.run), snapshot)

    def test_private_value_in_missing_row_is_rejected(self):
        self.sent(); snapshot = self.snapshot({'r1': 'DB_MISSING'})
        snapshot['records'][0]['ids'] = {'credential': 'synthetic-omission'}
        with self.assertRaisesRegex(dr.ContractError, 'INVALID_SNAPSHOT_IDS'):
            dr.audit_records(dr.requests(self.run), snapshot)

    def test_final_snapshot_cannot_use_original_cluster(self):
        self.sent(); snapshot = self.snapshot(); snapshot['source_access_used'] = True
        with self.assertRaisesRegex(dr.ContractError, 'SNAPSHOT_SOURCE_DEPENDENCY'):
            dr.audit_records(dr.requests(self.run), snapshot, pristine=False)

    def test_invalid_functional_receipt_is_not_persisted(self):
        self.sent(); self.ready(); plan = self.plan()
        unsafe = {k: {'passed': 'synthetic-private-marker', 'samples': 1, 'at_utc': dr.utc_now()} for k in dr.FUNCTIONS}
        plan['functional']['argv'] = [sys.executable, '-c', 'print('+repr(json.dumps(unsafe))+')']
        result = dr.execute(self.run, plan)
        self.assertEqual(result['execution'], 'INCOMPLETE')
        self.assertNotIn('synthetic-private-marker', (self.run/'summary.json').read_text())
        self.assertEqual(result['functional_checks'], {})

    def test_racing_response_after_audit_is_rejected_when_replayed(self):
        self.sent(); self.ready()
        dr.append(self.run, 'initial_audit', {})
        dr.append(self.run, 'response', {'request_id': 'r1', 'http_status': None, 'outcome': 'UNKNOWN'})
        with self.assertRaisesRegex(dr.ContractError, 'RESPONSE_AFTER_AUDIT'):
            dr.requests(self.run)

    def test_object_missing_blocks_worker(self):
        self.sent(); result = dr.audit_records(dr.requests(self.run), self.snapshot({"r1": "OBJECT_MISSING"}))
        self.assertFalse(result["activation_allowed"])

    def test_real_hash_mismatch_is_not_restored(self):
        self.sent(); s = self.snapshot(); s["records"][0]["sha256"] = '0' * 64
        result = dr.audit_records(dr.requests(self.run), s)
        self.assertEqual(result["requests"][0]["restored_state"], "HASH_MISMATCH")
        self.assertFalse(result["activation_allowed"])

    def test_missing_observation_is_not_db_loss(self):
        self.sent(); s = self.snapshot(); s["records"] = []
        with self.assertRaisesRegex(dr.ContractError, "MISSING_SNAPSHOT_OBSERVATION"):
            dr.audit_records(dr.requests(self.run), s)

    def test_initial_state_rejects_started_worker_or_flyway(self):
        self.sent()
        for key in ["worker_started", "flyway_ran", "repair_ran", "source_access_used"]:
            with self.subTest(key=key):
                s = self.snapshot(); s[key] = True
                with self.assertRaisesRegex(dr.ContractError, "INITIAL_SNAPSHOT_NOT_PRISTINE|SNAPSHOT_SOURCE_DEPENDENCY"):
                    dr.audit_records(dr.requests(self.run), s)

    def test_success_stages_audit_before_activation_then_final_audit(self):
        self.sent(); self.ready(); result = dr.execute(self.run, self.plan())
        self.assertEqual(result["execution"], "PASS")
        self.assertEqual((self.root / 'calls.txt').read_text().splitlines(),
                         ['restore', 'snapshot', 'activate', 'functional', 'final_snapshot', 'cleanup'])
        kinds = [e["kind"] for e in dr.events(self.run)]
        self.assertLess(kinds.index('initial_audit'), kinds.index('activated'))
        self.assertLess(kinds.index('activated'), kinds.index('final_audit'))
        self.assertEqual(result["rpo_guarantee"], "NOT_EVALUATED")

    def test_initial_loss_preserved_when_final_snapshot_is_repaired(self):
        self.sent(); self.sent('r2', 'loss'); self.ready()
        result = dr.execute(self.run, self.plan(self.snapshot({'r2': 'DB_MISSING'})))
        self.assertEqual(result['execution'], 'PASS')
        self.assertEqual(result['initial_audit']['lost_or_incomplete_acknowledged'], 1)
        self.assertEqual(result['final_audit']['lost_or_incomplete_acknowledged'], 0)

    def test_audit_failure_never_activates_and_cleans_up(self):
        self.sent(); self.ready()
        result = dr.execute(self.run, self.plan(self.snapshot({'r1': 'HASH_MISMATCH'})))
        self.assertEqual(result['execution'], 'INCOMPLETE')
        self.assertEqual((self.root/'calls.txt').read_text().splitlines(), ['restore', 'snapshot', 'cleanup'])

    def test_restore_failure_cleans_up(self):
        self.sent(); self.ready()
        result = dr.execute(self.run, self.plan(fail='restore'))
        self.assertEqual(result['reason'], 'OPERATOR_RESTORE_FAILED')
        self.assertEqual((self.root/'calls.txt').read_text().splitlines(), ['restore', 'cleanup'])

    def test_missing_cleanup_blocks_all_actions(self):
        self.sent(); self.ready(); plan = self.plan(); plan.pop('cleanup')
        with self.assertRaisesRegex(dr.ContractError, 'INCOMPLETE_OPERATOR_PLAN'):
            dr.execute(self.run, plan)
        self.assertFalse((self.root/'calls.txt').exists())

    def test_bad_later_command_blocks_restore(self):
        self.sent(); self.ready(); plan = self.plan(); plan['activate']['argv'] = [33]
        with self.assertRaisesRegex(dr.ContractError, 'INVALID_OPERATOR_COMMAND'):
            dr.execute(self.run, plan)
        self.assertFalse((self.root/'calls.txt').exists())

    def test_cleanup_failure_is_not_pass(self):
        self.sent(); self.ready(); result = dr.execute(self.run, self.plan(fail='cleanup'))
        self.assertEqual(result['execution'], 'INCOMPLETE')
        self.assertEqual(result['reason'], 'OPERATOR_CLEANUP_FAILED')

    def test_finished_run_cannot_be_executed_again(self):
        self.sent(); self.ready(); plan = self.plan(); dr.execute(self.run, plan)
        with self.assertRaisesRegex(dr.ContractError, 'RUN_NOT_READY_OR_ALREADY_STARTED'):
            dr.execute(self.run, plan)


if __name__ == '__main__':
    unittest.main()
