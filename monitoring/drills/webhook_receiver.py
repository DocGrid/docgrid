"""Capture timestamped Alertmanager webhook payloads for local incident drills."""

import json
import os
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


EVENT_LOG = Path(os.environ["EVENT_LOG"])


class WebhookHandler(BaseHTTPRequestHandler):
    """Expose a health probe and append each alert delivery as one durable JSON line."""

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
        event = {
            "receivedAt": datetime.now(timezone.utc).isoformat(),
            "payload": payload,
        }
        with EVENT_LOG.open("a", encoding="utf-8") as output:
            output.write(json.dumps(event, separators=(",", ":")) + "\n")
            output.flush()

        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        """Suppress access logs so failures retain the useful experiment output."""


if __name__ == "__main__":
    EVENT_LOG.parent.mkdir(parents=True, exist_ok=True)
    HTTPServer(("0.0.0.0", 8080), WebhookHandler).serve_forever()
