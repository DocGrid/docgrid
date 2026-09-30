"""Exercise the exact embedded Patroni YAML editor without a live VM."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


GUARD = Path(__file__).with_name("standby_apply_delay_guard.sh")
MARKER = "python3 - \"$1\" \"$config\" \"$backup\" \"$delay_seconds\" <<'PY'\n"


class StandbyGuardConfigTest(unittest.TestCase):
    """Preserve original YAML bytes while toggling delay and promotion exclusion."""

    def setUp(self):
        source = GUARD.read_text()
        self.editor = source.split(MARKER, 1)[1].split("\nPY\n}", 1)[0]
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.config = Path(self.temporary.name) / "patroni.yml"
        self.backup = Path(self.temporary.name) / "original.yml"

    def edit(self, action):
        return subprocess.run([sys.executable, "-", action, str(self.config),
                               str(self.backup), "120"], input=self.editor,
                              text=True, capture_output=True, check=False)

    def test_apply_and_restore_preserve_original(self):
        """A real existing tags block gains only nofailover and then returns byte-for-byte."""
        original = (b"scope: docgrid\npostgresql:\n  data_dir: /data\ntags:\n"
                    b"  noloadbalance: false\n  clonefrom: false\n")
        self.config.write_bytes(original)
        self.backup.write_bytes(original)
        self.assertEqual(0, self.edit("apply").returncode)
        changed = self.config.read_bytes()
        self.assertIn(b"recovery_min_apply_delay: 120s", changed)
        self.assertIn(b"nofailover: true", changed)
        self.assertEqual(0, self.edit("restore").returncode)
        self.assertEqual(original, self.config.read_bytes())

    def test_preexisting_failover_policy_is_not_overwritten(self):
        """An owner-defined tag must stop the experiment before editing config."""
        original = b"postgresql:\n  data_dir: /data\ntags:\n  nofailover: false\n"
        self.config.write_bytes(original)
        self.backup.write_bytes(original)
        self.assertNotEqual(0, self.edit("apply").returncode)
        self.assertEqual(original, self.config.read_bytes())

    def test_concurrent_change_is_not_destroyed_on_restore(self):
        """Do not overwrite a config another administrator changed during the run."""
        original = b"postgresql:\n  data_dir: /data\n"
        self.config.write_bytes(original)
        self.backup.write_bytes(original)
        self.assertEqual(0, self.edit("apply").returncode)
        changed = self.config.read_bytes() + b"other: value\n"
        self.config.write_bytes(changed)
        self.assertNotEqual(0, self.edit("restore").returncode)
        self.assertEqual(changed, self.config.read_bytes())


if __name__ == "__main__":
    unittest.main()
