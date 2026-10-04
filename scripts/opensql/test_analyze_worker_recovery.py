#!/usr/bin/env python3
"""Regression checks for multi-document final-state Worker reconciliation."""

import unittest

from analyze_worker_recovery import analyze


class WorkerRecoveryAnalysisTest(unittest.TestCase):
    """Reject incomplete or duplicate indexing even when uploads succeeded."""

    def setUp(self):
        """Create one completed synthetic document without exposing DB IDs."""
        self.row = {
            "test_document": "ha424-pdf-01",
            "document_status": "INDEXED",
            "version_status": "INDEXED",
            "job_status": "INDEXED",
            "retry_count": "1",
            "chunks": "2",
            "embeddings": "2",
            "duplicate_chunks": "0",
            "duplicate_embeddings": "0",
        }

    def test_completed_retried_document_passes(self):
        """A retried, fully indexed document satisfies the fault criterion."""
        self.assertTrue(analyze([self.row], 1, True)["passed"])

    def test_missing_embedding_fails(self):
        """A final INDEXED label cannot hide a missing vector."""
        self.row["embeddings"] = "1"
        self.assertFalse(analyze([self.row], 1, True)["passed"])

    def test_no_retry_fails_fault_claim(self):
        """A finished batch without any retry is not Worker recovery evidence."""
        self.row["retry_count"] = "0"
        self.assertFalse(analyze([self.row], 1, True)["passed"])

    def test_duplicate_title_is_rejected(self):
        """One title cannot be counted twice to satisfy the target sample size."""
        with self.assertRaisesRegex(ValueError, "DOCUMENT_COUNT_MISMATCH"):
            analyze([self.row, self.row], 2, False)

    def test_empty_run_cannot_pass_as_zero_expected_documents(self):
        """A bad CLI target must never turn missing evidence into a pass."""
        with self.assertRaisesRegex(ValueError, "EXPECTED_DOCUMENT_COUNT_INVALID"):
            analyze([], 0, False)


if __name__ == "__main__":
    unittest.main()
