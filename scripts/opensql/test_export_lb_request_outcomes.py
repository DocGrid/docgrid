"""Verify LB log redaction and response-origin classification."""

import json
import unittest

from export_lb_request_outcomes import export


class ExportLbRequestOutcomesTest(unittest.TestCase):
    """Keep non-public endpoints out of the published fault evidence."""

    def test_classifies_both_error_origins_without_retaining_addresses(self):
        entries = [
            {"timestamp": "2026-10-04T11:48:43.000Z", "httpRequest": {
                "requestUrl": "http://192.0.2.1/api/ha-probe/writes?ha_request_id=ha435fault01-v1-i1",
                "remoteIp": "192.0.2.2", "status": 503, "latency": "0.123s"},
                "jsonPayload": {"proxyStatus": 'details="failed_to_pick_backend"'}},
            {"timestamp": "2026-10-04T11:48:44.000Z", "httpRequest": {
                "requestUrl": "http://192.0.2.1/api/ha-probe/writes?ha_request_id=ha435fault01-v1-i2",
                "status": 500, "latency": "5.001s"},
                "jsonPayload": {"proxyStatus": 'details="response_sent_by_backend"'}},
        ]
        rows, summary = export(json.dumps(entries), "ha435fault01")
        self.assertEqual(summary, {"500:backend_response": 1, "503:load_balancer_no_backend": 1})
        self.assertEqual(rows[0]["latency_ms"], 123.0)
        self.assertNotIn("192.0.2", json.dumps(rows))

    def test_rejects_duplicate_request_ids(self):
        entry = {"timestamp": "2026-10-04T11:48:43.000Z", "httpRequest": {
            "requestUrl": "http://192.0.2.1/?ha_request_id=ha435fault01-v1-i1",
            "status": 503, "latency": "0.1s"}}
        with self.assertRaises(ValueError):
            export(json.dumps([entry, entry]), "ha435fault01")

    def test_preserves_missing_status_as_client_disconnect_not_http_failure(self):
        entry = {"timestamp": "2026-10-04T11:48:43.000Z", "httpRequest": {
            "requestUrl": "http://192.0.2.1/?ha_request_id=ha435fault01-v1-i1",
            "latency": "10s"},
            "jsonPayload": {"proxyStatus": 'details="client_disconnected_before_any_response"'}}
        rows, summary = export(json.dumps([entry]), "ha435fault01")
        self.assertEqual(rows[0]["http_status"], "")
        self.assertEqual(summary, {"no_http_status:client_disconnected": 1})


if __name__ == "__main__":
    unittest.main()
