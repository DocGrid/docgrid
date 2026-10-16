"""Verify the demo seed script's pure decisions without a running DocGrid server."""

import re
import unittest

from seed_demo_corpus import (
    CONTENT_TYPES,
    DEFAULT_CORPUS_DIR,
    classify,
    diagnose,
    idempotency_key,
    load_manifest,
)

# Backend InitialUploadRequestService.KEY_PATTERN과 같은 허용 문자·길이다.
SERVER_KEY_PATTERN = re.compile(r"[A-Za-z0-9._~-]{1,128}")
EMBEDDING_URL = "http://localhost:8000"


class IdempotencyKeyTest(unittest.TestCase):
    """Same bytes must replay the same upload, and the key must pass server validation."""

    def test_same_bytes_produce_same_valid_key(self):
        key = idempotency_key(b"demo")
        self.assertEqual(key, idempotency_key(b"demo"))
        self.assertTrue(SERVER_KEY_PATTERN.fullmatch(key))

    def test_different_bytes_produce_different_keys(self):
        self.assertNotEqual(idempotency_key(b"demo-a"), idempotency_key(b"demo-b"))


class ManifestTest(unittest.TestCase):
    """The committed corpus must stay uploadable by the script."""

    def test_committed_corpus_is_complete(self):
        documents = load_manifest(DEFAULT_CORPUS_DIR)
        self.assertGreaterEqual(len(documents), 10)
        self.assertEqual(len(documents), len({d["file"] for d in documents}))
        for document in documents:
            self.assertIn((DEFAULT_CORPUS_DIR / document["file"]).suffix, CONTENT_TYPES)
            self.assertTrue(0 < len(document["title"]) <= 500)


class ClassifyTest(unittest.TestCase):
    """Status API responses map to the four states the wait loop understands."""

    def test_indexed_document(self):
        self.assertEqual("INDEXED", classify({
            "documentStatus": "INDEXED",
            "currentVersion": {"versionNo": 1, "status": "INDEXED"},
            "processingVersion": None,
        }))

    def test_new_upload_waits_for_worker(self):
        self.assertEqual("PENDING", classify({
            "documentStatus": "UPLOADED", "currentVersion": None,
            "processingVersion": {"versionNo": 1, "status": "UPLOADED", "jobStatus": "PENDING"},
        }))

    def test_claimed_or_retrying_job_is_processing(self):
        for version_status, job_status in (("PARSING", "PROCESSING"), ("EMBEDDING", "PENDING")):
            self.assertEqual("PROCESSING", classify({
                "documentStatus": "INDEXING", "currentVersion": None,
                "processingVersion": {"versionNo": 1, "status": version_status, "jobStatus": job_status},
            }))

    def test_failed_job(self):
        self.assertEqual("FAILED", classify({
            "documentStatus": "FAILED", "currentVersion": None,
            "processingVersion": {"versionNo": 1, "status": "FAILED", "jobStatus": "FAILED"},
        }))


class DiagnoseTest(unittest.TestCase):
    """Hints must point at the worker or the embedding server based on observed progress."""

    def test_no_hint_when_everything_indexed(self):
        self.assertEqual([], diagnose({1: "INDEXED"}, {1}, False, EMBEDDING_URL))

    def test_worker_hint_when_no_job_started(self):
        hints = diagnose({1: "PENDING", 2: "PENDING"}, set(), True, EMBEDDING_URL)
        self.assertEqual(1, len(hints))
        self.assertIn("INDEXING_WORKER_ENABLED", hints[0])

    def test_embedding_hint_first_when_server_not_ready(self):
        hints = diagnose({1: "PROCESSING"}, {1}, False, EMBEDDING_URL)
        self.assertEqual(1, len(hints))
        self.assertIn("/health/ready", hints[0])

    def test_embedding_hint_for_failed_documents(self):
        hints = diagnose({1: "FAILED", 2: "INDEXED"}, {1, 2}, False, EMBEDDING_URL)
        self.assertIn("/health/ready", hints[0])

    def test_slow_hint_when_worker_and_server_are_up(self):
        hints = diagnose({1: "PROCESSING"}, {1}, True, EMBEDDING_URL)
        self.assertIn("--timeout", hints[0])


if __name__ == "__main__":
    unittest.main()
