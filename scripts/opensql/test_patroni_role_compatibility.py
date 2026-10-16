"""실제 HTTP에서 역할 정규화·scope 거부·쓰기 차단·상류 실패를 검증한다."""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch
import patroni_role_compatibility as adapter


class UpstreamHandler(BaseHTTPRequestHandler):
    """합성 Patroni JSON만 반환하는 HTTP 시험 상류이며 실제 VM에 접근하지 않는다."""
    def log_message(self, *_args):
        pass
    def do_GET(self):
        self.server.calls += 1
        value = self.server.identity if self.path == "/patroni" else self.server.cluster
        raw = json.dumps(value).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(raw)


class CompatibilityHttpTest(unittest.TestCase):
    """어댑터가 실제 응답을 바꾸는 범위와 실패 시 차단을 HTTP 경계에서 검증한다."""
    def setUp(self):
        self.upstream = ThreadingHTTPServer(("127.0.0.1", 0), UpstreamHandler)
        self.upstream.calls = 0
        self.upstream.identity = {"role": "primary", "patroni": {"scope": "isolated-test"}}
        self.upstream.cluster = {"members": [{"name": "synthetic-primary", "role": "leader"}, {"name": "synthetic-standby", "role": "sync_standby", "lag": 0}]}
        self.adapter = adapter.CompatibilityServer(("127.0.0.1", 0), "http://127.0.0.1:" + str(self.upstream.server_port), "isolated-test")
        for server in (self.upstream, self.adapter):
            threading.Thread(target=server.serve_forever, daemon=True).start()
            self.addCleanup(server.server_close)
            self.addCleanup(server.shutdown)
    def request(self, path="/cluster", method="GET"):
        try:
            with urlopen(Request("http://127.0.0.1:" + str(self.adapter.server_port) + path, method=method), timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, None
    def test_sync_alias_preserves_leader_and_metadata(self):
        code, body = self.request()
        self.assertEqual(code, 200)
        self.assertEqual(body["members"][0]["role"], "leader")
        self.assertEqual(body["members"][1], {"name": "synthetic-standby", "role": "replica", "lag": 0})
    def test_quorum_alias_is_replica(self):
        self.upstream.cluster["members"][1]["role"] = "quorum_standby"
        self.assertEqual(self.request()[1]["members"][1]["role"], "replica")
    def test_scope_mismatch_is_closed(self):
        self.upstream.identity["patroni"]["scope"] = "other"
        self.assertEqual(self.request()[0], 502)
    def test_unknown_role_is_closed(self):
        self.upstream.cluster["members"][1]["role"] = "unknown"
        self.assertEqual(self.request()[0], 502)
    def test_missing_members_is_closed(self):
        self.upstream.cluster = {}
        self.assertEqual(self.request()[0], 502)
    def test_mutations_never_reach_upstream(self):
        for method in ("POST", "PUT", "PATCH", "DELETE"):
            self.assertEqual(self.request("/config", method)[0], 405)
        self.assertEqual(self.upstream.calls, 0)
    def test_unknown_path_never_reaches_upstream(self):
        self.assertEqual(self.request("/config")[0], 404)
        self.assertEqual(self.upstream.calls, 0)
    def test_upstream_failure_is_closed(self):
        with patch.object(adapter, "read_json", side_effect=OSError("synthetic unavailable")):
            self.assertEqual(self.request()[0], 502)
    def test_identity_is_fresh_and_primary_is_preserved(self):
        self.assertEqual(self.request("/patroni")[1]["role"], "primary")
        self.upstream.identity["role"] = "replica"
        self.assertEqual(self.request("/patroni")[1]["role"], "replica")


if __name__ == "__main__":
    unittest.main()
