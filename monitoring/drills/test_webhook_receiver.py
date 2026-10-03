"""Unit tests for pre-recording sanitization of local Alertmanager webhook evidence."""

import importlib.util
import os
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("webhook_receiver.py")
SPEC = importlib.util.spec_from_file_location("docgrid_webhook_receiver", MODULE_PATH)
with patch.dict(os.environ, {"EVENT_LOG": "/tmp/docgrid-webhook-test.jsonl"}):
    webhook_receiver = importlib.util.module_from_spec(SPEC)
    SPEC.loader.exec_module(webhook_receiver)


class WebhookEvidenceSanitizationTest(unittest.TestCase):
    """Verify internal callback addresses never reach the persisted evidence payload."""

    def test_sanitize_payload_replaces_internal_urls_at_every_depth(self):
        payload = {
            "externalURL": "http://alertmanager-container:9093",
            "alerts": [{
                "generatorURL": "http://prometheus-container:9090/graph",
                "labels": {"alertname": "EmbeddingProviderDown"},
            }],
        }

        sanitized = webhook_receiver.sanitize_payload(payload)

        self.assertEqual(
            webhook_receiver.INTERNAL_URL_OMITTED,
            sanitized["externalURL"],
        )
        self.assertEqual(
            webhook_receiver.INTERNAL_URL_OMITTED,
            sanitized["alerts"][0]["generatorURL"],
        )
        self.assertEqual(
            "EmbeddingProviderDown",
            sanitized["alerts"][0]["labels"]["alertname"],
        )


if __name__ == "__main__":
    unittest.main()
