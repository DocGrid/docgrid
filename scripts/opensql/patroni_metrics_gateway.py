#!/usr/bin/env python3
"""Expose only Patroni metrics while keeping its unauthenticated REST API private.

This read-only gateway runs on each DB host, forwards one fixed GET path to the
local Patroni listener, and does not accept management methods or paths.
"""

from __future__ import annotations

import argparse
import ipaddress
from http.client import HTTPConnection, HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


UPSTREAM_HOST = "127.0.0.1"
UPSTREAM_PORT = 8008
UPSTREAM_PATH = "/metrics"
MAX_METRICS_BYTES = 1024 * 1024
PRIVATE_NETWORKS = tuple(
    ipaddress.ip_network(cidr) for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)


class MetricsOnlyHandler(BaseHTTPRequestHandler):
    """Forward GET /metrics only, without exposing Patroni's management API."""

    def log_message(self, format_string: str, *args: object) -> None:
        """Avoid writing caller addresses or untrusted paths to host logs."""

    def do_GET(self) -> None:
        """Fetch bounded metrics from the fixed loopback upstream."""
        if self.path != UPSTREAM_PATH:
            self.send_error(404)
            return
        connection = HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT, timeout=2)
        try:
            # 1. A fixed HTTPConnection cannot follow a redirect to another host.
            connection.request("GET", UPSTREAM_PATH)
            response = connection.getresponse()
            if response.status != 200:
                raise ValueError("upstream status")
            body = response.read(MAX_METRICS_BYTES + 1)
            if len(body) > MAX_METRICS_BYTES:
                raise ValueError("upstream body too large")
        except (OSError, HTTPException, ValueError):
            self.send_error(502)
            return
        finally:
            connection.close()
        # 2. Return the scrape payload without proxying upstream headers or redirects.
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        """Reject Patroni management writes even from the allowed observer VM."""
        self.send_error(405)

    def do_PATCH(self) -> None:
        """Reject Patroni configuration writes."""
        self.send_error(405)

    def do_DELETE(self) -> None:
        """Reject Patroni resource deletion."""
        self.send_error(405)


def validate_private_bind(value: str) -> str:
    """Reject wildcard and non-RFC1918 addresses, including special-use ranges."""
    address = ipaddress.ip_address(value)
    if address.version != 4 or not any(address in network for network in PRIVATE_NETWORKS):
        raise ValueError("--bind must be an RFC1918 IPv4 address")
    return str(address)


def main() -> None:
    """Bind only a supplied RFC1918 interface address on the metrics-only port."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True)
    args = parser.parse_args()
    try:
        bind_address = validate_private_bind(args.bind)
    except ValueError as error:
        parser.error(str(error))
    with ThreadingHTTPServer((bind_address, 18008), MetricsOnlyHandler) as server:
        server.serve_forever(poll_interval=0.5)


if __name__ == "__main__":
    main()
