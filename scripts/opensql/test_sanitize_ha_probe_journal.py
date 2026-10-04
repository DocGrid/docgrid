"""Check that journal extraction never emits infrastructure or exception text."""

import json
import unittest

from sanitize_ha_probe_journal import sanitize


class JournalSanitizerTest(unittest.TestCase):
    """Keep one probe request's fixed diagnostic fields and discard all other journal data."""

    def test_accepts_failure_without_copying_untrusted_context(self):
        source = json.dumps({
            "__REALTIME_TIMESTAMP": "1791050400123456",
            "_HOSTNAME": "private-host",
            "MESSAGE": "prefix HA_PROBE_EXCEPTION run=ha418sw00001 "
                       "request=ha418sw00001-v1-i2 phase=TX_BEGIN "
                       "type=CannotCreateTransactionException sqlstate=none status=500 "
                       "password=private-secret",
        })

        result = sanitize(source, "ha418sw00001")

        self.assertEqual(result["request_id"], "ha418sw00001-v1-i2")
        self.assertEqual(result["type"], "CannotCreateTransactionException")
        self.assertEqual(result["status"], 500)
        self.assertNotIn("private", json.dumps(result))

    def test_rejects_other_run_and_unexpected_phase(self):
        source = json.dumps({
            "__REALTIME_TIMESTAMP": "1791050400123456",
            "MESSAGE": "HA_PROBE_THROW run=ha418px00001 request=ha418px00001-v1-i2 "
                       "phase=UNKNOWN type=RuntimeException sqlstate=none",
        })

        self.assertIsNone(sanitize(source, "ha418px00001"))
        self.assertIsNone(sanitize(source, "ha418sw00001"))


if __name__ == "__main__":
    unittest.main()
