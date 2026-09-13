"""Capture Alertmanager webhook payloads for the isolated routing E2E test."""

import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


EVENT_LOG = Path(os.environ["EVENT_LOG"])


class WebhookHandler(BaseHTTPRequestHandler):
    """Accept health probes and append each valid alert payload as one JSON line."""

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        if self.path != "/alerts":
            self.send_error(404)
            return

        content_length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(content_length))
        with EVENT_LOG.open("a", encoding="utf-8") as output:
            output.write(json.dumps(payload, separators=(",", ":")) + "\n")

        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        """Keep the test output focused on assertion failures."""


if __name__ == "__main__":
    EVENT_LOG.parent.mkdir(parents=True, exist_ok=True)
    HTTPServer(("0.0.0.0", 8080), WebhookHandler).serve_forever()
