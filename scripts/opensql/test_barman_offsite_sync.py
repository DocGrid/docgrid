"""Tests for Barman's offsite completion and failure boundaries."""

import hashlib
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

import barman_offsite_sync as sync


class BarmanOffsiteSyncTest(unittest.TestCase):
    """Verify export integrity and explicit local/remote WAL completion boundaries."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config = {
            "server": "test_server",
            "destination": "gs://private-test-bucket/barman",
            "work_dir": self.root / "work",
            "status_file": self.root / "status.json",
            "max_lag_seconds": 900,
        }
        self.backup_id = "20261017T000000"

    def make_export(self, checksum_valid=True):
        directory = self.config["work_dir"] / self.backup_id
        directory.mkdir(parents=True)
        content = b"complete backup with catalog and required WAL"
        suffix = hashlib.sha256(content).hexdigest()[:8] if checksum_valid else "00000000"
        archive = directory / f"backup-export-test_server-{self.backup_id}-20261017T010000-{suffix}.tar"
        archive.write_bytes(content)
        return archive

    def test_interrupted_export_is_never_uploaded(self):
        self.make_export(checksum_valid=False)
        with self.assertRaises(sync.SyncError) as raised:
            sync.stage_export("test_server", self.backup_id, self.config["work_dir"])
        self.assertEqual(raised.exception.step, "export_checksum")

    def test_export_from_another_backup_id_is_rejected(self):
        archive = self.make_export()
        archive.rename(archive.with_name(archive.name.replace(self.backup_id, "20261016T000000")))
        with self.assertRaises(sync.SyncError) as raised:
            sync.stage_export("test_server", self.backup_id, self.config["work_dir"])
        self.assertEqual(raised.exception.step, "export_identity")

    def test_barman_status_column_spacing_is_accepted(self):
        def fake_run(command, step, **_):
            if step == "barman_list_backup":
                return self.backup_id + "\n"
            if step == "barman_show_backup":
                return "  Status                 : DONE\n"
            return ""

        with patch.object(sync, "run", side_effect=fake_run):
            self.assertEqual(sync.completed_backups("test_server"), [self.backup_id])

    def test_marker_is_published_only_after_archive_checksum_matches(self):
        archive = self.make_export()
        commands = []
        uploaded = False

        def fake_run(command, step, **_):
            nonlocal uploaded
            commands.append(step)
            if step == "gcs_marker":
                return None
            if step == "local_checksum":
                return "Hash (crc32c): abc123\n"
            if step == "gcs_stat":
                if not uploaded:
                    return None
                return f"Content-Length: {archive.stat().st_size}\nHash (crc32c): abc123\n"
            if step == "archive_upload":
                uploaded = True
            if step == "marker_readback":
                return (archive.parent / "complete.json").read_text()
            return ""

        with patch.object(sync, "run", side_effect=fake_run):
            sync.sync_backup(self.config, self.backup_id)
        self.assertLess(commands.index("archive_upload"), commands.index("marker_upload"))
        self.assertLess(commands.index("gcs_stat"), commands.index("marker_upload"))
        self.assertFalse(archive.exists())

    def test_failed_marker_upload_keeps_export_for_retry(self):
        archive = self.make_export()
        first = True
        uploaded = False
        uploads = 0

        def fake_run(command, step, **_):
            nonlocal first, uploaded, uploads
            if step == "gcs_marker":
                return None
            if step == "local_checksum":
                return "Hash (crc32c): abc123\n"
            if step == "gcs_stat":
                if not uploaded:
                    return None
                return f"Content-Length: {archive.stat().st_size}\nHash (crc32c): abc123\n"
            if step == "archive_upload":
                uploaded = True
                uploads += 1
            if step == "marker_upload" and first:
                first = False
                raise sync.SyncError("marker_upload")
            if step == "marker_readback":
                return (archive.parent / "complete.json").read_text()
            return ""

        with patch.object(sync, "run", side_effect=fake_run):
            with self.assertRaises(sync.SyncError):
                sync.sync_backup(self.config, self.backup_id)
            self.assertTrue(archive.exists())
            sync.sync_backup(self.config, self.backup_id)
        self.assertFalse(archive.exists())
        self.assertEqual(uploads, 1)

    def test_missing_wal_segments_are_excluded_from_success_count(self):
        directory = self.root / "wals"
        directory.mkdir()
        (directory / "000000010000000000000001").write_bytes(b"complete")
        (directory / "00000002.history").write_bytes(b"timeline")
        (directory / "000000010000000000000002.partial").write_bytes(b"in progress")
        with patch.object(sync, "run", return_value="") as command:
            count = sync.sync_wals(self.config, directory)
        self.assertEqual(count, 2)
        pattern = command.call_args.args[0][6]
        self.assertIsNone(re.match(pattern, "hash/000000010000000000000001"))
        self.assertIsNone(re.match(pattern, "hash/00000002.history"))
        self.assertIsNotNone(re.match(pattern, "hash/000000010000000000000002.partial"))
        self.assertIsNotNone(re.match(pattern, "xlog.db"))

    def test_health_uses_last_success_and_reports_failure(self):
        sync.write_status(self.config["status_file"], {
            "last_success_utc": "2020-01-01T00:00:00+00:00",
            "last_error": "wal_sync",
        })
        self.assertEqual(sync.check_health(self.config), 1)
        state = json.loads(self.config["status_file"].read_text())
        self.assertEqual(state["last_error"], "wal_sync")

    def test_failed_sync_keeps_last_success_timestamp(self):
        previous = "2026-10-17T00:00:00+00:00"
        sync.write_status(self.config["status_file"], {
            "last_success_utc": previous,
            "last_error": None,
        })
        with patch.object(sync, "barman_paths", return_value={"wals_directory": self.root / "wals"}), \
                patch.object(sync, "completed_backups", return_value=[self.backup_id]), \
                patch.object(sync, "sync_wals", side_effect=sync.SyncError("wal_sync")):
            with self.assertRaises(sync.SyncError):
                sync.sync(self.config)
        state = json.loads(self.config["status_file"].read_text())
        self.assertEqual(state["last_success_utc"], previous)
        self.assertEqual(state["last_error"], "wal_sync")

    def test_delayed_required_wal_is_archived_before_returning(self):
        directory = self.root / "wals"
        directory.mkdir()
        wal = "000000010000000000000007"
        target = directory / wal[:16] / wal
        calls = []
        clock = [0.0]
        def archive(*args, **kwargs):
            calls.append(args)
            if len(calls) == 3:
                target.parent.mkdir()
                target.write_bytes(b"sealed WAL")
            return ""
        with patch.object(sync, "run", side_effect=archive), \
                patch.object(sync.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(sync.time, "sleep", side_effect=lambda n: clock.__setitem__(0, clock[0] + n)):
            self.assertEqual(sync.wait_for_archived_wal("test_server", directory, wal), target)
        self.assertEqual(len(calls), 3)
        self.assertEqual(clock[0], 2.0)

    def test_partial_or_unrelated_wal_does_not_satisfy_barrier(self):
        directory = self.root / "wals"
        directory.mkdir()
        wal = "000000010000000000000007"
        (directory / (wal + ".partial")).write_bytes(b"active")
        (directory / "000000010000000000000006").write_bytes(b"older")
        clock = [0.0]
        with patch.object(sync, "run", return_value=""), \
                patch.object(sync.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(sync.time, "sleep", side_effect=lambda n: clock.__setitem__(0, clock[0] + n)):
            with self.assertRaises(sync.SyncError) as raised:
                sync.wait_for_archived_wal("test_server", directory, wal, timeout_seconds=3)
        self.assertEqual(raised.exception.step, "required_wal_timeout")

    def test_invalid_required_names_are_rejected_before_archiving(self):
        for wal in ["../escape", "00000001.history", "000000010000000000000007.partial", "", None]:
            with self.subTest(wal=wal), patch.object(sync, "run") as command:
                with self.assertRaises(sync.SyncError):
                    sync.wait_for_archived_wal("test_server", self.root, wal)
                command.assert_not_called()

    def test_required_wait_rejects_unbounded_or_invalid_timing(self):
        for timeout in [0, -1, 301, float("nan"), float("inf"), True]:
            with self.subTest(timeout=timeout), patch.object(sync, "run") as command:
                with self.assertRaises(sync.SyncError):
                    sync.wait_for_archived_wal("test_server", self.root, "0" * 24, timeout_seconds=timeout)
                command.assert_not_called()

    def test_archive_failure_is_not_reported_as_timeout_or_success(self):
        with patch.object(sync, "run", side_effect=sync.SyncError("required_wal_archive")):
            with self.assertRaises(sync.SyncError) as raised:
                sync.wait_for_archived_wal("test_server", self.root, "0" * 24)
        self.assertEqual(raised.exception.step, "required_wal_archive")

    def test_remote_required_wal_is_verified_before_success(self):
        wal = "000000010000000000000007"
        directory = self.root / "wals"
        target = directory / wal[:16] / wal
        target.parent.mkdir(parents=True)
        target.write_bytes(b"sealed WAL")
        with patch.object(sync, "barman_paths", return_value={"wals_directory": directory}), \
                patch.object(sync, "completed_backups", return_value=[self.backup_id]), \
                patch.object(sync, "wait_for_archived_wal", return_value=target) as barrier, \
                patch.object(sync, "local_crc32c", return_value="abc123"), \
                patch.object(sync, "sync_wals", return_value=1), \
                patch.object(sync, "remote_metadata", return_value={"size": 10, "crc32c": "abc123"}) as metadata, \
                patch.object(sync, "sync_backup"):
            sync.sync(self.config, required_wal=wal)
        barrier.assert_called_once()
        self.assertTrue(metadata.call_args.args[0].endswith("/wals/" + wal[:16] + "/" + wal))
        state = json.loads(self.config["status_file"].read_text())
        self.assertTrue(state['required_wal_verified'])
        self.assertEqual(state['required_wal'], wal)

    def test_remote_required_wal_mismatch_keeps_previous_success(self):
        wal = "000000010000000000000007"
        directory = self.root / "wals"
        target = directory / wal[:16] / wal
        target.parent.mkdir(parents=True)
        target.write_bytes(b"sealed WAL")
        previous = "2026-10-17T00:00:00+00:00"
        sync.write_status(self.config['status_file'], {'last_success_utc': previous, 'last_error': None})
        with patch.object(sync, "barman_paths", return_value={"wals_directory": directory}), \
                patch.object(sync, "completed_backups", return_value=[self.backup_id]), \
                patch.object(sync, "wait_for_archived_wal", return_value=target), \
                patch.object(sync, "local_crc32c", return_value="abc123"), \
                patch.object(sync, "sync_wals", return_value=1), \
                patch.object(sync, "remote_metadata", return_value=None):
            with self.assertRaises(sync.SyncError) as raised:
                sync.sync(self.config, required_wal=wal)
        self.assertEqual(raised.exception.step, 'required_wal_remote_integrity')
        state = json.loads(self.config['status_file'].read_text())
        self.assertEqual(state['last_success_utc'], previous)
        self.assertEqual(state['last_error'], 'required_wal_remote_integrity')


if __name__ == "__main__":
    unittest.main()
