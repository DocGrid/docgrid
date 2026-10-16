"""Regression checks for document-original copy auditing."""

from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch

import verify_gcs_original_preservation as audit


class GcsOriginalPreservationTest(unittest.TestCase):
    """Check inventory parsing and the missing, changed, and retained boundaries."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source_dir = self.root / "source"
        self.destination_dir = self.root / "destination"
        self.source_dir.mkdir()
        self.destination_dir.mkdir()
        self.source = {"versions/document.pdf": {"size": 5, "generation": "101"}}
        self.destination = {"versions/document.pdf": {"size": 5, "generation": "202"}}
        for directory in (self.source_dir, self.destination_dir):
            (directory / "versions").mkdir()
            (directory / "versions/document.pdf").write_bytes(b"hello")

    def test_metadata_preserves_different_source_and_copy_generations(self):
        listing = """gs://example/versions/document.pdf:\n    Content-Length: 5\n    Generation: 101\nTOTAL: 1 objects\n"""
        self.assertEqual(audit.parse_listing(listing, "gs://example"), self.source)
        records, counts = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        self.assertEqual(records["versions/document.pdf"]["source_generation"], "101")
        self.assertEqual(records["versions/document.pdf"]["destination_generation"], "202")
        self.assertEqual(counts["source"], 1)

    def test_metadata_rejects_empty_path_component(self):
        listing = "gs://example/versions//document.pdf:\n    Content-Length: 5\n    Generation: 101\n"
        with self.assertRaises(audit.AuditError) as raised:
            audit.parse_listing(listing, "gs://example")
        self.assertEqual(raised.exception.step, "metadata_key")

    def test_destination_can_count_and_ignore_invalid_path(self):
        listing = "gs://example/versions//probe.txt:\n    Content-Length: 5\n    Generation: 101\ngs://example/versions/document.pdf:\n    Content-Length: 5\n    Generation: 102\n"
        ignored = []
        result = audit.parse_listing(listing, "gs://example", ignored)
        self.assertEqual(ignored, ["versions//probe.txt"])
        self.assertEqual(list(result), ["versions/document.pdf"])

    def test_config_requires_independent_buckets_and_normalized_prefix(self):
        path = self.root / "config.json"
        base = {"source": "gs://source-bucket", "destination": "gs://source-bucket/copy",
                "manifest": str(self.root / "manifest.json"), "status": str(self.root / "status.json")}
        path.write_text(json.dumps(base))
        with self.assertRaises(audit.AuditError):
            audit.load_config(path)
        base["destination"] = "gs://other-bucket/copy//nested"
        path.write_text(json.dumps(base))
        with self.assertRaises(audit.AuditError):
            audit.load_config(path)

    def test_missing_copy_fails_instead_of_publishing_success(self):
        with self.assertRaises(audit.AuditError) as raised:
            audit.compare(self.source, {}, self.source_dir, self.destination_dir, {})
        self.assertEqual(raised.exception.step, "missing_copy")

    def test_changed_bytes_fail_even_when_sizes_match(self):
        (self.destination_dir / "versions/document.pdf").write_bytes(b"world")
        with self.assertRaises(audit.AuditError) as raised:
            audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        self.assertEqual(raised.exception.step, "sha256_mismatch")

    def test_deleted_source_keeps_previously_verified_copy(self):
        old, _ = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        records, counts = audit.compare({}, self.destination, self.source_dir, self.destination_dir, old)
        self.assertEqual(counts["retained"], 1)
        self.assertEqual(counts["unattributed_retained"], 0)
        self.assertEqual(records["versions/document.pdf"]["source_generation"], "101")

    def test_retained_copy_requires_previous_hash(self):
        old, _ = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        old["versions/document.pdf"]["sha256"] = "not-a-hash"
        with self.assertRaises(audit.AuditError) as raised:
            audit.compare({}, self.destination, self.source_dir, self.destination_dir, old)
        self.assertEqual(raised.exception.step, "previous_hash_missing")

    def test_previous_generations_reject_same_byte_replacement(self):
        old, _ = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        new_destination = {"versions/document.pdf": {"size": 5, "generation": "203"}}
        with self.assertRaises(audit.AuditError) as raised:
            audit.compare(self.source, new_destination, self.source_dir, self.destination_dir, old)
        self.assertEqual(raised.exception.step, "destination_generation_changed")

    def test_retained_generation_change_fails(self):
        old, _ = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        new_destination = {"versions/document.pdf": {"size": 5, "generation": "203"}}
        with self.assertRaises(audit.AuditError) as raised:
            audit.compare({}, new_destination, self.source_dir, self.destination_dir, old)
        self.assertEqual(raised.exception.step, "destination_generation_changed")

    def test_incremental_audit_reuses_only_previously_hashed_generations(self):
        old, _ = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        source_new, destination_new = audit.select_downloads(self.source, self.destination, old)
        self.assertEqual((source_new, destination_new), ({}, {}))
        records, counts = audit.compare(self.source, self.destination, self.root / "absent-source",
                                        self.root / "absent-destination", old)
        self.assertEqual(records, old)
        self.assertEqual(counts["source"], 1)

    def test_new_generation_requires_both_bytes(self):
        source_new, destination_new = audit.select_downloads(self.source, self.destination, {})
        self.assertEqual(source_new, self.source)
        self.assertEqual(destination_new, self.destination)

    def test_download_pins_the_listed_generation(self):
        with patch.object(audit, "run") as command:
            audit.download_objects(self.source, "gs://source-bucket", self.source_dir, "source_download")
        self.assertEqual(command.call_args.args[0][3],
                         "gs://source-bucket/versions/document.pdf#101")

    def test_missing_previously_verified_copy_fails_even_after_source_deletion(self):
        old, _ = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        with self.assertRaises(audit.AuditError) as raised:
            audit.select_downloads({}, {}, old)
        self.assertEqual(raised.exception.step, "previous_copy_missing")

    def test_retained_copy_reuses_verified_destination_generation(self):
        old, _ = audit.compare(self.source, self.destination, self.source_dir, self.destination_dir, {})
        self.assertEqual(audit.select_downloads({}, self.destination, old), ({}, {}))
        records, counts = audit.compare({}, self.destination, self.root / "absent-source",
                                        self.root / "absent-destination", old)
        self.assertEqual(counts["retained"], 1)
        self.assertEqual(records, old)


if __name__ == "__main__":
    unittest.main()
