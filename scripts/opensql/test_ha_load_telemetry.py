"""Regression tests for secret-free k6 and host telemetry alignment."""

from __future__ import annotations

import csv
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("ha_load_telemetry.py")
SPEC = importlib.util.spec_from_file_location("ha_load_telemetry", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("계측기를 읽을 수 없습니다")
TELEMETRY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TELEMETRY)


class HaLoadTelemetryTest(unittest.TestCase):
    """Separate offered-load drops, live VUs and VM capacity on one UTC scale."""

    def setUp(self):
        """Give every fixture a fresh run directory with no reusable result path."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.metrics = self.directory / "metrics.jsonl"
        self.host = self.directory / "host.csv"
        self.summary = self.directory / "summary.json"
        self.output = self.directory / "telemetry.csv"

    def write_fixture(self, *, unsafe_tag=False, dropped=2):
        """Model two k6 seconds and independent 1s /proc samples."""
        points = [
            ("2026-10-04T01:00:00.000Z", "vus", 4),
            ("2026-10-04T01:00:00.000Z", "vus_max", 40),
            ("2026-10-04T01:00:00.000Z", "http_reqs", 1),
            ("2026-10-04T01:00:00.000Z", "http_req_waiting", 50),
            ("2026-10-04T01:00:00.000Z", "http_req_blocked", 2),
            ("2026-10-04T01:00:00.000Z", "iteration_duration", 60),
            ("2026-10-04T01:00:01.000Z", "vus", 36),
            ("2026-10-04T01:00:01.000Z", "vus_max", 40),
            ("2026-10-04T01:00:01.000Z", "dropped_iterations", dropped),
            ("2026-10-04T01:00:01.000Z", "http_reqs", 1),
            ("2026-10-04T01:00:01.000Z", "http_req_waiting", 970),
            ("2026-10-04T01:00:01.000Z", "iteration_duration", 1000),
        ]
        self.metrics.write_text("".join(json.dumps({
            "type": "Point", "metric": metric,
            "data": {"time": at, "value": value,
                     "tags": {"url": "http://internal.invalid"} if unsafe_tag and metric == "vus" else None},
        }) + "\n" for at, metric, value in points), encoding="utf-8")
        self.host.write_text(
            "at_utc,host_cpu_pct,mem_available_mib,process_cpu_pct,process_rss_mib,process_threads\n"
            "2026-10-04T01:00:00.300Z,,1024,,30,10\n"
            "2026-10-04T01:00:01.300Z,95,900,150,80,18\n",
            encoding="utf-8",
        )
        self.summary.write_text(json.dumps({"dropped_iterations": 2, "http_requests": 2}),
                                encoding="utf-8")

    def test_joins_seconds_and_preserves_dropped_count(self):
        """The failure second must show VUs, waits and host pressure together."""
        self.write_fixture()
        result = TELEMETRY.summarize(self.metrics, self.host, self.summary, self.output)
        self.assertEqual({"dropped": 2, "requests": 2, "seconds": 2,
                          "host_seconds": 2, "active_vu_peak": 36,
                          "allocated_vu_peak": 40}, result)
        with self.output.open(newline="", encoding="utf-8") as source:
            rows = list(csv.DictReader(source))
        self.assertEqual("2", rows[1]["미전송_건"])
        self.assertEqual("36.0", rows[1]["활성_VU_최대"])
        self.assertEqual("970.0", rows[1]["HTTP_응답대기_p95_ms"])
        self.assertEqual("95", rows[1]["호스트_CPU_%"])
        self.assertEqual("150", rows[1]["k6_CPU_%"])
        self.assertEqual("2026-10-04T01:00:01Z", rows[1]["시각_UTC"])

    def test_rejects_raw_url_tag_before_derived_file_is_written(self):
        """A changed k6 configuration cannot silently publish an internal URL."""
        self.write_fixture(unsafe_tag=True)
        with self.assertRaisesRegex(ValueError, "허용되지 않은 태그"):
            TELEMETRY.summarize(self.metrics, self.host, self.summary, self.output)
        self.assertFalse(self.output.exists())

    def test_rejects_missing_metric_points(self):
        """Do not claim a complete 1s timeline when dropped samples went missing."""
        self.write_fixture(dropped=1)
        with self.assertRaisesRegex(ValueError, "합계"):
            TELEMETRY.summarize(self.metrics, self.host, self.summary, self.output)
        self.assertFalse(self.output.exists())

    def test_proc_parsers_ignore_process_name_and_guest_double_count(self):
        """Parse stable numeric fields even when the process name has whitespace."""
        self.assertEqual((36, 9), TELEMETRY.cpu_ticks("cpu  1 2 3 4 5 6 7 8 9 10\n"))
        stat = "123 (k6 worker) S " + " ".join(["0"] * 10 + ["42", "8"])
        self.assertEqual(50, TELEMETRY.process_ticks(stat))

    def test_rejects_naive_timestamps(self):
        """Local wall time must never be silently joined to UTC."""
        with self.assertRaisesRegex(ValueError, "시간대"):
            TELEMETRY.utc_second("2026-10-04T01:00:00")


if __name__ == "__main__":
    unittest.main()
