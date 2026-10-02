"""Keep HTTP recovery and sampled TCP movement separate in HA evidence."""

import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SOURCE = Path(__file__).with_name("analyze_ha_proxy_recovery.py")
FIELDS = (
    "at_utc", "role", "proxy_a_established", "proxy_b_established",
    "proxy_a_new_cumulative", "proxy_b_new_cumulative", "hikari_active",
    "hikari_idle", "hikari_pending", "hikari_total", "hikari_timeout_total",
    "probe_201_total", "metrics_ok",
)


class HaProxyRecoveryAnalysisTest(unittest.TestCase):
    """Check a known route switch and reject evidence with unsafe columns."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.run_dir = Path(self.temporary.name) / "ha391test001"
        self.run_dir.mkdir()
        with (self.run_dir / "requests.csv").open("w", newline="") as output:
            writer = csv.DictWriter(output, fieldnames=(
                "request_id", "operation", "sent_at", "completed_at",
                "outcome", "http_status", "reason",
            ))
            writer.writeheader()
            writer.writerow({"request_id": "r1", "operation": "ha_probe_write",
                             "sent_at": "2026-10-02T00:00:00.220Z",
                             "completed_at": "2026-10-02T00:00:00.250Z",
                             "outcome": "FAILED", "http_status": 500, "reason": ""})
            writer.writerow({"request_id": "r2", "operation": "ha_probe_write",
                             "sent_at": "2026-10-02T00:00:00.260Z",
                             "completed_at": "2026-10-02T00:00:00.300Z",
                             "outcome": "ACKNOWLEDGED", "http_status": 201, "reason": ""})
        for role in ("app-a", "app-b"):
            self.write_samples(role)

    def write_samples(self, role, extra_column=False):
        with (self.run_dir / f"{role}-connections.csv").open("w", newline="") as output:
            fields = (*FIELDS, "private_address") if extra_column else FIELDS
            writer = csv.DictWriter(output, fieldnames=fields)
            writer.writeheader()
            for millis, a, b, new_b in (
                (0, 2, 3, 1), (500, 0, 5, 2), (1000, 0, 5, 2),
                (1500, 0, 5, 2), (2000, 0, 5, 2),
            ):
                row = {
                    "at_utc": f"2026-10-02T00:00:{millis // 1000:02d}.{millis % 1000:03d}Z",
                    "role": role, "proxy_a_established": a, "proxy_b_established": b,
                    "proxy_a_new_cumulative": 0, "proxy_b_new_cumulative": new_b,
                    "hikari_active": 0, "hikari_idle": 5, "hikari_pending": 0,
                    "hikari_total": 5, "hikari_timeout_total": 0,
                    "probe_201_total": 10, "metrics_ok": 1,
                }
                if extra_column:
                    row["private_address"] = "192.0.2.1"
                writer.writerow(row)

    def execute(self):
        return subprocess.run([
            sys.executable, str(SOURCE), "--run-dir", str(self.run_dir),
            "--fault-target", "a", "--kill-at", "2026-10-02T00:00:00.200Z",
            "--ready-at", "2026-10-02T00:00:01.800Z",
            "--output", str(self.run_dir / "analysis.json"),
        ], capture_output=True, text=True)

    def test_new_connection_uses_pre_fault_cumulative_and_sample_bounds(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        analysis = json.loads((self.run_dir / "analysis.json").read_text())
        self.assertEqual(50, analysis["http"]["last_failure_to_new_success_ms"])
        self.assertEqual(1600, analysis["fault_marker_to_ready_ms"])
        for role in ("app-a", "app-b"):
            app = analysis["apps"][role]
            self.assertEqual(300, app["first_new_survivor_connection"]["since_kill_upper_bound_ms"])
            self.assertEqual(0, app["first_new_survivor_connection"]["since_kill_lower_bound_ms"])
            self.assertEqual(300, app["survivor_pool_stable_three_samples"]["since_kill_upper_bound_ms"])
            self.assertEqual(1300, app["survivor_pool_stable_three_samples"]["confirmed_since_kill_ms"])
            self.assertIsNone(app["first_new_recovered_proxy_connection_after_ready"])

    def test_unexpected_private_column_rejects_output(self):
        self.write_samples("app-b", extra_column=True)
        result = self.execute()
        self.assertEqual(2, result.returncode)
        self.assertFalse((self.run_dir / "analysis.json").exists())


if __name__ == "__main__":
    unittest.main()
