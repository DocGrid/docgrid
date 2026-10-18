"""실제 모델 없이 API 결과를 제어해 거짓 성공과 원장 오염을 방지한다."""
import contextlib
import io
import json
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

import seed_demo_corpus as seed


class ControlledClient:
    """예제 API 계약의 성공·오류만 제어하며 네트워크나 DB에 접근하지 않는다."""

    def __init__(self, search_code=200, empty=False, wrong_document=False,
                 corrupt_file=False, rag_status="SUCCESS", valid_citation=True):
        self.base_url = "http://localhost:8080"
        self.search_code = search_code
        self.empty = empty
        self.wrong_document = wrong_document
        self.corrupt_file = corrupt_file
        self.rag_status = rag_status
        self.valid_citation = valid_citation
        self.search_calls = 0
        self.status_calls = 0
        self.documents = seed.load_manifest(seed.DEFAULT_CORPUS_DIR)

    def call_json(self, method, path, body):
        self.search_calls += 1
        results = [] if self.empty else [
            {"documentId": 999 if self.wrong_document else i, "chunkId": i + 100}
            for i in [1, 6, 11]]
        return self.search_code, {"data": {"queryId": self.search_calls, "results": results,
            "ragStatus": self.rag_status, "answer": "합성 답변",
            "citations": [{"documentId": 1, "chunkId": 101 if self.valid_citation else 999,
                           "quotedText": "합성 근거"}]}}

    def call(self, method, path):
        if path.endswith("/content"):
            return 200, {"data": {"content": "경조금 50 만원, 경조휴가 5 일"}}
        self.status_calls += 1
        return 500, {}

    def call_bytes(self, method, path, limit):
        document_id = int(path.split("/")[-2])
        content = (seed.DEFAULT_CORPUS_DIR / self.documents[document_id - 1]["file"]).read_bytes()
        return 200, b"wrong" if self.corrupt_file else content


class DemoVerdictTest(unittest.TestCase):
    """인덱싱 성공이 검색·원본·선택 RAG 실패를 덮어쓰지 못하는지 검사한다."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def run_seed(self, client=None, name="run", extra=None, states=None):
        client = client or ControlledClient()
        documents = seed.load_manifest(seed.DEFAULT_CORPUS_DIR)
        mapping = {d["file"]: i for i, d in enumerate(documents, 1)}
        with contextlib.ExitStack() as stack:
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(patch.object(seed, "DocGridClient", return_value=client))
            stack.enter_context(patch.object(seed, "embedding_ready", return_value=True))
            stack.enter_context(patch.object(seed, "authenticate"))
            stack.enter_context(patch.object(seed, "read_password", return_value="synthetic-not-secret"))
            stack.enter_context(patch.object(seed, "upload", side_effect=lambda c, p, d:
                                             {"documentId": mapping[d["file"]]}))
            stack.enter_context(patch.object(seed, "wait_for_indexing", return_value=(
                states if states is not None else {i: "INDEXED" for i in mapping.values()}, set())))
            result = seed.run(["--report-dir", str(self.root / name), "--rag-wait", "0"] + (extra or []))
        return result, json.loads((self.root / name / "summary.json").read_text())

    def test_full_search_scope_passes_without_rag_polling(self):
        client = ControlledClient(rag_status="FAILED")
        code, report = self.run_seed(client)
        self.assertEqual(0, code)
        self.assertEqual(3, client.search_calls)
        self.assertEqual(0, client.status_calls)
        self.assertEqual("NOT_RUN", report["stages"]["rag"]["status"])
        self.assertEqual(15, report["stages"]["download"]["passed"])

    def test_search_http_error_fails_after_all_documents_indexed(self):
        client = ControlledClient(search_code=500)
        code, report = self.run_seed(client)
        self.assertEqual(1, code)
        self.assertEqual(3, client.search_calls)
        self.assertEqual("PASS", report["stages"]["indexing"]["status"])
        self.assertEqual("FAIL", report["stages"]["search"]["status"])

    def test_empty_search_results_fail(self):
        self.assertEqual(1, self.run_seed(ControlledClient(empty=True))[0])

    def test_missing_expected_document_fails(self):
        self.assertEqual(1, self.run_seed(ControlledClient(wrong_document=True))[0])

    def test_missing_query_id_is_not_a_valid_search(self):
        client = ControlledClient()
        original = client.call_json
        def response(*args):
            status, body = original(*args)
            body["data"]["queryId"] = None
            return status, body
        client.call_json = response
        self.assertEqual(1, self.run_seed(client)[0])

    def test_invalid_candidate_is_not_a_valid_search(self):
        client = ControlledClient()
        original = client.call_json
        def response(*args):
            status, body = original(*args)
            body["data"]["results"][0]["chunkId"] = None
            return status, body
        client.call_json = response
        self.assertEqual(1, self.run_seed(client)[0])

    def test_download_hash_mismatch_fails(self):
        code, report = self.run_seed(ControlledClient(corrupt_file=True))
        self.assertEqual(1, code)
        self.assertEqual(0, report["stages"]["download"]["passed"])

    def test_table_text_missing_fails(self):
        client = ControlledClient()
        client.call = lambda *args: (200, {"data": {"content": "표 내용 없음"}})
        self.assertEqual(1, self.run_seed(client)[0])

    def test_rag_scope_requires_success_and_valid_citations(self):
        self.assertEqual(0, self.run_seed(extra=["--scope", "rag"])[0])

    def test_rag_failed_is_not_success(self):
        code, report = self.run_seed(ControlledClient(rag_status="FAILED"), extra=["--scope", "rag"])
        self.assertEqual(1, code)
        self.assertEqual("FAIL", report["stages"]["rag"]["status"])

    def test_rag_incomplete_is_not_success(self):
        self.assertEqual(1, self.run_seed(ControlledClient(rag_status="PROCESSING"),
                                        extra=["--scope", "rag"])[0])

    def test_citation_outside_search_candidates_fails(self):
        self.assertEqual(1, self.run_seed(ControlledClient(valid_citation=False),
                                        extra=["--scope", "rag"])[0])

    def test_unrun_required_stages_cannot_pass(self):
        code, report = self.run_seed(states={i: "PENDING" for i in range(1, 16)})
        self.assertEqual(1, code)
        self.assertEqual("NOT_RUN", report["stages"]["download"]["status"])

    def test_previous_document_ids_are_compared(self):
        self.run_seed(name="first")
        code, report = self.run_seed(name="second", extra=[
            "--previous-report", str(self.root / "first" / "summary.json")])
        self.assertEqual(0, code)
        self.assertEqual(15, report["stages"]["replay"]["passed"])

    def test_different_previous_document_id_fails(self):
        self.run_seed(name="first")
        previous = self.root / "first" / "summary.json"
        data = json.loads(previous.read_text())
        data["documents"][next(iter(data["documents"]))] = 999
        previous.write_text(json.dumps(data))
        self.assertEqual(1, self.run_seed(name="second", extra=["--previous-report", str(previous)])[0])

    def test_changed_target_refuses_replay_before_upload(self):
        self.run_seed(name="first")
        code, report = self.run_seed(name="second", extra=["--email", "other@example.invalid",
            "--previous-report", str(self.root / "first" / "summary.json")])
        self.assertEqual(2, code)
        self.assertEqual("NOT_RUN", report["stages"]["upload"]["status"])

    def test_existing_report_is_never_overwritten(self):
        directory = self.root / "existing"
        directory.mkdir()
        marker = directory / "summary.json"
        marker.write_text("preserve")
        with self.assertRaises(seed.SeedError):
            seed.run(["--report-dir", str(directory)])
        self.assertEqual("preserve", marker.read_text())

    def test_event_redaction_happens_before_storage(self):
        args = seed.parse_args(["--report-dir", str(self.root / "redaction")])
        report = seed.DemoReport(args)
        seed.ACTIVE_REPORT = report
        seed.SECRET_VALUES.append("synthetic-private-value")
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                seed.log("synthetic-private-value user@example.invalid https://user:password@server.invalid")
        finally:
            seed.ACTIVE_REPORT = None
            seed.SECRET_VALUES.clear()
            report.finish(1)
        events = (report.directory / "events.jsonl").read_text()
        self.assertNotIn("synthetic-private-value", events)
        self.assertNotIn("user@example.invalid", events)
        self.assertNotIn("server.invalid", events)

    def test_query_credentials_are_redacted_without_knowing_the_password(self):
        text = seed.safe_text('/login?email=fixture%40example.invalid&password=synthetic-unregistered-password&token=opaque-fixture-token')
        self.assertNotIn('fixture%40example.invalid', text)
        self.assertNotIn('synthetic-unregistered-password', text)
        self.assertNotIn('opaque-fixture-token', text)


class HttpBoundaryTest(unittest.TestCase):
    """HTTP 오류 코드 해석과 원본 응답 크기 제한의 실제 클라이언트 경계를 확인한다."""

    def test_http_error_preserves_code_for_signup_decision(self):
        error = urllib.error.HTTPError("http://localhost:8080/auth/login", 401, "controlled", {},
                                       io.BytesIO(b'{"code":"USER-004"}'))
        client = seed.DocGridClient("http://localhost:8080")
        with patch.object(client.opener, "open", side_effect=error):
            status, body = client.call("POST", "/auth/login")
        self.assertEqual((401, "USER-004"), (status, body["code"]))

    def test_oversized_original_is_rejected(self):
        response = unittest.mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = b"12345"
        client = seed.DocGridClient("http://localhost:8080")
        with patch.object(client.opener, "open", return_value=response):
            with self.assertRaises(seed.SeedError):
                client.call_bytes("GET", "/file", limit=4)

    def test_redirect_does_not_forward_credentials(self):
        self.assertIsNone(seed.NoRedirect().redirect_request(None, None, 302, 'controlled', {},
                                                           'http://remote.invalid/'))


if __name__ == "__main__":
    unittest.main()
