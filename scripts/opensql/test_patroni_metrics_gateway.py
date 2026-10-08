"""Deterministic checks for the metrics-only Patroni access boundary."""

from __future__ import annotations

import http.client
import threading
import unittest
from unittest.mock import patch

from patroni_metrics_gateway import MetricsOnlyHandler, ThreadingHTTPServer, validate_private_bind


class FakeUpstream:
    """A fixed in-memory Patroni metrics response for the gateway tests."""

    status = 200

    def read(self, _):
        """Return only a small Prometheus metrics payload."""
        return b"patroni_primary 1\n"


class PatroniMetricsGatewayTest(unittest.TestCase):
    """Ensure no management method or path reaches the Patroni REST listener."""

    def setUp(self):
        """Start a local ephemeral gateway for one isolated test."""
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), MetricsOnlyHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.thread.join, 2)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(self, method: str, path: str):
        """Send one local request and return status and body."""
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        self.addCleanup(connection.close)
        connection.request(method, path)
        response = connection.getresponse()
        return response.status, response.read()

    def test_only_exact_metrics_get_reaches_fixed_upstream(self):
        """The upstream URL is fixed even when the caller controls the request."""
        with patch("patroni_metrics_gateway.HTTPConnection") as connection:
            connection.return_value.getresponse.return_value = FakeUpstream()
            status, body = self.request("GET", "/metrics")
        self.assertEqual(200, status)
        self.assertEqual(b"patroni_primary 1\n", body)
        connection.assert_called_once_with("127.0.0.1", 8008, timeout=2)
        connection.return_value.request.assert_called_once_with("GET", "/metrics")

    def test_other_get_path_never_reaches_upstream(self):
        """Management reads and query variants are not forwarded."""
        with patch("patroni_metrics_gateway.HTTPConnection") as fetch:
            for path in ("/patroni", "/config", "/metrics?extra=1"):
                status, _ = self.request("GET", path)
                self.assertEqual(404, status)
        fetch.assert_not_called()

    def test_management_write_methods_never_reach_upstream(self):
        """Switchover, configuration and deletion calls cannot pass this port."""
        with patch("patroni_metrics_gateway.HTTPConnection") as fetch:
            for method in ("POST", "PATCH", "DELETE"):
                status, _ = self.request(method, "/switchover")
                self.assertEqual(405, status)
        fetch.assert_not_called()

    def test_upstream_failure_is_a_failed_scrape(self):
        """Patroni errors must not be turned into a healthy empty scrape."""
        with patch("patroni_metrics_gateway.HTTPConnection") as connection:
            connection.return_value.request.side_effect = OSError("unavailable")
            status, _ = self.request("GET", "/metrics")
        self.assertEqual(502, status)

    def test_bind_requires_rfc1918_interface(self):
        """A wildcard or special-use address must not widen the listener."""
        self.assertEqual("10.0.0.7", validate_private_bind("10.0.0.7"))
        for address in ("0.0.0.0", "127.0.0.1", "169.254.1.1", "255.255.255.255", "8.8.8.8", "::1"):
            with self.subTest(address=address), self.assertRaises(ValueError):
                validate_private_bind(address)


if __name__ == "__main__":
    unittest.main()
