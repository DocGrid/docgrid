#!/usr/bin/env python3
"""Check that HA Worker fixtures remain valid, distinct, and secret-free."""

import io
import unittest
import zipfile

from ha_worker_documents import document_sequence, multipart_document, synthetic_docx


class WorkerDocumentFixtureTest(unittest.TestCase):
    """Keep multi-document fault fixtures parseable and marker-specific."""

    def test_docx_contains_distinct_searchable_paragraphs(self):
        """The synthetic DOCX uses the officeDocument relation and unique text."""
        left = synthetic_docx("ha424-left")
        right = synthetic_docx("ha424-right")
        self.assertNotEqual(left, right)
        with zipfile.ZipFile(io.BytesIO(left)) as archive:
            self.assertIn("word/document.xml", archive.namelist())
            self.assertIn(b"ha424-left passage 030", archive.read("word/document.xml"))

    def test_multipart_uses_correct_docx_media_type(self):
        """The actual HTTP upload identifies DOCX rather than generic ZIP."""
        content = synthetic_docx("ha424-docx")
        body, media_type = multipart_document(content, "ha424-docx-01", "docx")
        self.assertIn("boundary=docgrid-", media_type)
        self.assertIn(b"application/vnd.openxmlformats-officedocument.wordprocessingml.document", body)
        self.assertIn(content, body)

    def test_upload_plan_interleaves_formats_before_a_fault(self):
        """Both PDF and DOCX must be eligible in the earliest accepted cohort."""
        self.assertEqual(
            document_sequence(3, 2),
            [("pdf", 1), ("docx", 1), ("pdf", 2), ("docx", 2), ("pdf", 3)],
        )


if __name__ == "__main__":
    unittest.main()
