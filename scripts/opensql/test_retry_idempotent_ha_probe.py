"""Regression tests for bounded HA replay and the two independent DB comparisons."""

import csv
import base64
import json
import stat
import tempfile
import time
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from threading import Thread
from types import SimpleNamespace

from retry_idempotent_ha_probe import (prepare, prepare_k6_all, run_retries, send,
                                        validate_token_lifetime, verify_after)


RUN_ID = "ha777retry"
AT = "2026-10-10T00:00:00.000Z"


class IdempotentHaRetryTest(unittest.TestCase):
    """Never let HTTP replay erase the evidence of an earlier missing 201."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.events = self.root / "first-safe.jsonl"
        self.summary = self.root / "k6-summary.json"
        self.db_before = self.root / "db-before.csv"
        self.db_after = self.root / "db-after.csv"
        self.output = self.root / "replay"
        self.token = self.root / "jwt"
        self.token.write_text("synthetic-test-token", encoding="utf-8")
        self.token.chmod(0o600)
        self.requests = [
            ("ack", "acknowledged", 201),
            ("failed", "failed", 500),
            ("unknown", "unknown", None),
            ("auth", "failed", 403),
        ]
        self.write_events()
        self.write_db(self.db_before, {"ack": 1, "unknown": 1})

    def request_id(self, name):
        return f"{RUN_ID}-v1-i{next(index for index, item in enumerate(self.requests) if item[0] == name)}"

    def write_events(self):
        events = []
        for index, (_, outcome, status) in enumerate(self.requests):
            request_id = f"{RUN_ID}-v1-i{index}"
            events.append({"at": AT, "event_id": f"{request_id}-sent", "kind": "sent",
                           "operation": "ha_probe_idempotent_write", "request_id": request_id,
                           "run_id": RUN_ID})
            terminal = {"at": AT, "event_id": f"{request_id}-{outcome}", "kind": outcome,
                        "request_id": request_id, "run_id": RUN_ID}
            if status is None:
                terminal["reason"] = "timeout"
            else:
                terminal["http_status"] = status
            events.append(terminal)
        self.events.write_text("".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
        self.summary.write_text(json.dumps({"run_id": RUN_ID, "iterations": len(self.requests),
                                            "http_requests": len(self.requests), "dropped_iterations": 0}),
                                encoding="utf-8")

    def write_db(self, path, rows):
        with path.open("w", newline="", encoding="utf-8") as output:
            writer = csv.writer(output)
            writer.writerow(("request_id", "row_count"))
            for name, count in rows.items():
                writer.writerow((self.request_id(name), count))

    def run_args(self):
        return SimpleNamespace(run_id=RUN_ID, events=self.events, summary=self.summary,
                               db_before=self.db_before,
                               target="http://127.0.0.1/api/ha-probe/idempotent-writes",
                               token_file=self.token, output_dir=self.output,
                               max_retries=3, max_rate=100)

    def verify_args(self):
        return SimpleNamespace(run_id=RUN_ID, events=self.events, summary=self.summary,
                               db_before=self.db_before, db_after=self.db_after,
                               replay_dir=self.output)

    def prepare_all_args(self):
        return SimpleNamespace(run_id=RUN_ID, events=self.events, summary=self.summary,
                               db_before=self.db_before, output_dir=self.output)

    def verify_all_args(self):
        args = self.verify_args()
        args.replay_summary = self.output / "k6-replay-summary.json"
        return args

    def write_all_replay_evidence(self, statuses, dropped=0):
        # Model k6's allowlisted sent/result stream, not a fabricated DB assertion.
        events = []
        for name, status in statuses.items():
            request_id = self.request_id(name)
            for kind in ("sent", "result"):
                event = {"run_id": RUN_ID, "request_id": request_id, "attempt": 2,
                         "kind": kind, "at": AT}
                if kind == "result":
                    event["http_status"] = status
                events.append(event)
        (self.output / "재전송-시도.jsonl").write_text(
            "".join(json.dumps(event) + "\n" for event in events), encoding="utf-8")
        counts = {"200": sum(value == 200 for value in statuses.values()),
                  "201": sum(value == 201 for value in statuses.values()),
                  "other": sum(value not in (None, 200, 201) for value in statuses.values()),
                  "unknown": sum(value is None for value in statuses.values())}
        (self.output / "k6-replay-summary.json").write_text(json.dumps({
            "source_run_id": RUN_ID, "manifest_requests": len(self.requests),
            "iterations": len(self.requests), "http_requests": len(statuses),
            "dropped_iterations": dropped, "status_counts": counts,
        }), encoding="utf-8")

    def test_failed_and_unknown_replay_without_resending_201_or_403(self):
        calls = []

        def sender(target, token, run_id, request_id):
            calls.append(request_id)
            return 201 if request_id == self.request_id("failed") else 200

        result = run_retries(self.run_args(), sender=sender, sleeper=lambda _: None)
        self.assertEqual(2, result["retry_candidates"])
        self.assertEqual(2, result["http_resolved"])
        self.assertEqual({self.request_id("failed"), self.request_id("unknown")}, set(calls))
        self.assertEqual(0o600, stat.S_IMODE((self.output / "재전송-시도.jsonl").stat().st_mode))
        self.assertNotIn("synthetic-test-token", (self.output / "재전송-시도.jsonl").read_text())
        self.write_db(self.db_after, {"ack": 1, "failed": 1, "unknown": 1})
        final = verify_after(self.verify_args())
        self.assertTrue(final["final_pass"])
        self.assertEqual(1, final["retry_201_new"])
        self.assertEqual(1, final["retry_200_already_present_before"])
        self.assertEqual(0, final["initial_201_missing_before_retry"])

    def test_missing_initial_201_is_preserved_even_after_replay(self):
        self.write_db(self.db_before, {"unknown": 1})
        run_retries(self.run_args(), sender=lambda *args: 201, sleeper=lambda _: None)
        self.write_db(self.db_after, {"failed": 1, "unknown": 1})
        final = verify_after(self.verify_args())
        self.assertEqual(1, final["initial_201_missing_before_retry"])
        self.assertFalse(final["final_pass"])

    def test_repeated_5xx_has_bounded_attempts_and_remains_unresolved(self):
        calls = []

        def sender(*args):
            calls.append(args[-1])
            return 500

        args = self.run_args()
        args.max_retries = 2
        result = run_retries(args, sender=sender, sleeper=lambda _: None)
        self.assertEqual(4, result["retry_attempts"])
        self.assertEqual(2, result["http_unresolved"])
        self.assertEqual(4, len(calls))

    def test_503_is_replayed_but_403_is_not(self):
        self.requests = [("unavailable", "failed", 503), ("auth", "failed", 403)]
        self.write_events()
        self.write_db(self.db_before, {})
        calls = []

        def sender(*args):
            calls.append(args[-1])
            return 201

        result = run_retries(self.run_args(), sender=sender, sleeper=lambda _: None)
        self.assertEqual(1, result["retry_candidates"])
        self.assertEqual([self.request_id("unavailable")], calls)

    def test_409_on_replay_is_not_reported_as_recovered(self):
        run_retries(self.run_args(), sender=lambda *args: 409, sleeper=lambda _: None)
        self.write_db(self.db_after, {"ack": 1, "unknown": 1})
        final = verify_after(self.verify_args())
        self.assertEqual(1, final["retry_conflict_409"])
        self.assertFalse(final["final_pass"])

    def test_expired_jwt_stops_remaining_candidates(self):
        calls = []

        def sender(*args):
            calls.append(args[-1])
            return 401

        result = run_retries(self.run_args(), sender=sender, sleeper=lambda _: None)
        self.assertEqual(401, result["stopped_on_non_retryable_http"])
        self.assertEqual(1, len(calls))
        self.assertEqual(2, result["http_unresolved"])

    def test_unsafe_target_or_token_mode_blocks_before_creating_output(self):
        args = self.run_args()
        args.target = "http://example.com/api/ha-probe/idempotent-writes"
        with self.assertRaisesRegex(ValueError, "내부 IPv4"):
            run_retries(args)
        self.assertFalse(self.output.exists())
        args.target = "http://127.0.0.1/api/ha-probe/idempotent-writes"
        self.token.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "0600"):
            run_retries(args)
        self.assertFalse(self.output.exists())

    def test_duplicate_db_rows_are_reported_as_failure(self):
        self.write_db(self.db_before, {"ack": 2, "unknown": 1})
        _, pre = prepare(self.events, self.summary, self.db_before, RUN_ID)
        self.assertEqual(1, pre["db_duplicate_ids_before_retry"])
        with self.assertRaisesRegex(ValueError, "DB 중복"):
            run_retries(self.run_args(), sender=lambda *args: 201, sleeper=lambda _: None)
        saved = json.loads((self.output / "재전송-전-대조.json").read_text())
        self.assertEqual(1, saved["db_duplicate_ids_before_retry"])
        self.assertFalse((self.output / "재전송-시도.jsonl").exists())

    def test_redirect_is_returned_without_following_or_forwarding_jwt(self):
        calls = []

        class RedirectHandler(BaseHTTPRequestHandler):
            """Return a redirect and expose whether a second request was made."""

            def do_POST(self):
                calls.append(self.path)
                self.send_response(302)
                self.send_header("Location", "/unexpected")
                self.end_headers()

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), RedirectHandler)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            target = f"http://127.0.0.1:{server.server_port}/api/ha-probe/idempotent-writes"
            self.assertEqual(302, send(target, "synthetic-test-token", RUN_ID, self.request_id("failed")))
            self.assertEqual(["/api/ha-probe/idempotent-writes"], calls)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_k6_all_manifest_reuses_every_sent_id_and_verifies_db(self):
        prepared = prepare_k6_all(self.prepare_all_args())
        self.assertEqual(4, prepared["request_count"])
        self.assertEqual(0o700, stat.S_IMODE(self.output.stat().st_mode))
        manifest = json.loads((self.output / "재전송-전체-목록.json").read_text())
        self.assertEqual([self.request_id(name) for name, _, _ in self.requests],
                         manifest["request_ids"])
        self.assertEqual(0o600, stat.S_IMODE((self.output / "재전송-전체-목록.json").stat().st_mode))
        self.write_all_replay_evidence({"ack": 200, "failed": 201, "unknown": 200, "auth": 201})
        self.write_db(self.db_after, {name: 1 for name, _, _ in self.requests})
        final = verify_after(self.verify_all_args())
        self.assertTrue(final["idempotency_pass"])
        self.assertTrue(final["final_pass"])
        self.assertEqual(2, final["retry_200_already_present_before"])
        self.assertEqual(2, final["retry_201_new"])
        self.assertEqual(2, final["db_rows_added"])
        self.assertEqual(2, final["db_unique_ids_added"])
        self.assertEqual(0, final["db_duplicate_ids_after_retry"])

    def test_k6_all_does_not_hide_acknowledged_data_loss(self):
        self.write_db(self.db_before, {"unknown": 1})
        prepare_k6_all(self.prepare_all_args())
        self.write_all_replay_evidence({"ack": 201, "failed": 201, "unknown": 200, "auth": 201})
        self.write_db(self.db_after, {name: 1 for name, _, _ in self.requests})
        final = verify_after(self.verify_all_args())
        self.assertTrue(final["idempotency_pass"])
        self.assertFalse(final["final_pass"])
        self.assertEqual(1, final["initial_201_missing_before_retry"])

    def test_k6_all_detects_missing_or_dropped_replay(self):
        prepare_k6_all(self.prepare_all_args())
        self.write_all_replay_evidence({"ack": 200, "failed": 201, "unknown": 200}, dropped=1)
        self.write_db(self.db_after, {"ack": 1, "failed": 1, "unknown": 1})
        final = verify_after(self.verify_all_args())
        self.assertFalse(final["k6_replay_evidence_valid"])
        self.assertFalse(final["idempotency_pass"])
        self.assertEqual(1, final["final_missing_sent_ids"])

    def test_k6_all_rejects_manifest_tampering(self):
        prepare_k6_all(self.prepare_all_args())
        manifest_path = self.output / "재전송-전체-목록.json"
        manifest = json.loads(manifest_path.read_text())
        manifest["request_ids"].pop()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        self.write_all_replay_evidence({"ack": 200, "failed": 201, "unknown": 200, "auth": 201})
        self.write_db(self.db_after, {name: 1 for name, _, _ in self.requests})
        with self.assertRaisesRegex(ValueError, "원본 원장"):
            verify_after(self.verify_all_args())

    def test_k6_all_requires_jwt_valid_through_expected_duration(self):
        def synthetic_jwt(expiry):
            encoded = base64.urlsafe_b64encode(json.dumps({"exp": expiry}).encode()).decode().rstrip("=")
            return f"header.{encoded}.signature"

        validate_token_lifetime(synthetic_jwt(int(time.time()) + 300), 180)
        with self.assertRaisesRegex(ValueError, "부족"):
            validate_token_lifetime(synthetic_jwt(int(time.time()) + 120), 180)
        with self.assertRaisesRegex(ValueError, "확인할 수 없습니다"):
            validate_token_lifetime("not-a-jwt", 180)


if __name__ == "__main__":
    unittest.main()
