#!/usr/bin/env python3
"""Record numeric k6-host samples and join safe k6 metrics on a UTC timeline."""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import signal
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


SAFE_TAGS = set()
TIMINGS = {"http_req_waiting", "http_req_blocked", "iteration_duration"}
CSV_FIELDS = (
    "시각_UTC", "미전송_건", "HTTP_요청_건", "활성_VU_최대", "확보_VU_최대",
    "HTTP_응답대기_p95_ms", "연결대기_p95_ms", "반복수행_p95_ms",
    "호스트_CPU_%", "호스트_가용메모리_MiB", "k6_CPU_%", "k6_RSS_MiB", "k6_스레드_수",
)
HOST_FIELDS = (
    "at_utc", "host_cpu_pct", "mem_available_mib", "process_cpu_pct",
    "process_rss_mib", "process_threads",
)


def private_output(path):
    """Create one owner-only artifact without overwriting an earlier run."""
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    return os.fdopen(descriptor, "w", encoding="utf-8", newline="")


def utc_now():
    """Timestamp samples in the same UTC scale as k6 metric points."""
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def utc_second(value):
    """Reject timestamps without a zone rather than silently aligning wrong seconds."""
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None:
        raise ValueError("UTC 시각에 시간대가 없습니다")
    return instant.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def cpu_ticks(text):
    """Return host total and idle ticks without double-counting Linux guest ticks."""
    fields = text.splitlines()[0].split()
    if fields[0] != "cpu" or len(fields) < 9:
        raise ValueError("/proc/stat CPU 값이 올바르지 않습니다")
    values = [int(value) for value in fields[1:9]]
    return sum(values), values[3] + values[4]


def process_ticks(text):
    """Read utime/stime after the parenthesized process name, which may contain spaces."""
    tail = text[text.rfind(")") + 2:].split()
    if len(tail) < 13:
        raise ValueError("/proc/PID/stat 값이 올바르지 않습니다")
    return int(tail[11]) + int(tail[12])


def host_snapshot(pid):
    """Read only numeric /proc fields; command lines, addresses and env never enter logs."""
    total, idle = cpu_ticks(Path("/proc/stat").read_text(encoding="ascii"))
    memory = Path("/proc/meminfo").read_text(encoding="ascii")
    available_kib = next(int(line.split()[1]) for line in memory.splitlines()
                         if line.startswith("MemAvailable:"))
    process = Path(f"/proc/{pid}")
    ticks = process_ticks((process / "stat").read_text(encoding="ascii"))
    status = (process / "status").read_text(encoding="ascii")
    fields = {line.split(":", 1)[0]: line.split(":", 1)[1].strip()
              for line in status.splitlines() if ":" in line}
    return total, idle, ticks, round(available_kib / 1024, 2), round(
        int(fields["VmRSS"].split()[0]) / 1024, 2), int(fields["Threads"])


def sample(pid, output):
    """Stream one safe host/process sample per second until the caller stops us."""
    stopping = False

    def stop(_signal, _frame):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    with private_output(output) as destination:
        writer = csv.DictWriter(destination, fieldnames=HOST_FIELDS)
        writer.writeheader()
        destination.flush()
        prior = None
        while not stopping:
            try:
                now = time.monotonic()
                total, idle, ticks, available, rss, threads = host_snapshot(pid)
            except (FileNotFoundError, ProcessLookupError):
                break
            host_cpu = process_cpu = ""
            if prior is not None:
                elapsed = now - prior[0]
                total_delta = total - prior[1]
                if total_delta > 0 and elapsed > 0:
                    host_cpu = round(100 * (total_delta - (idle - prior[2])) / total_delta, 2)
                    process_cpu = round(100 * (ticks - prior[3]) /
                                        os.sysconf("SC_CLK_TCK") / elapsed, 2)
            writer.writerow(dict(zip(HOST_FIELDS, (
                utc_now(), host_cpu, available, process_cpu, rss, threads,
            ))))
            destination.flush()
            prior = now, total, idle, ticks
            time.sleep(1)


def p95(values):
    """Use nearest-rank within each second; this is not the whole-run k6 p95."""
    if not values:
        return ""
    ordered = sorted(values)
    return round(ordered[math.ceil(0.95 * len(ordered)) - 1], 2)


def summarize(metrics_path, host_path, summary_path, output_path):
    """Reject unsafe tags and incomplete streams before publishing a joined 1s CSV."""
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    buckets = defaultdict(lambda: {"dropped_iterations": 0, "http_reqs": 0,
                                   "vus": [], "vus_max": [], **{name: [] for name in TIMINGS}})
    with metrics_path.open(encoding="utf-8") as source:
        for line in source:
            item = json.loads(line)
            if item.get("type") != "Point":
                continue
            point = item["data"]
            tags = point.get("tags") or {}
            if not isinstance(tags, dict) or not set(tags) <= SAFE_TAGS:
                raise ValueError("k6 지표에 허용되지 않은 태그가 있습니다")
            metric = item["metric"]
            if metric not in {"dropped_iterations", "http_reqs", "vus", "vus_max"} | TIMINGS:
                continue
            value = float(point["value"])
            if not math.isfinite(value) or value < 0:
                raise ValueError("k6 지표 숫자가 올바르지 않습니다")
            if metric in {"dropped_iterations", "http_reqs"} and not value.is_integer():
                raise ValueError("k6 건수 지표가 정수가 아닙니다")
            second = utc_second(point["time"])
            if metric in {"dropped_iterations", "http_reqs"}:
                buckets[second][metric] += value
            else:
                buckets[second][metric].append(value)

    dropped = sum(bucket["dropped_iterations"] for bucket in buckets.values())
    requests = sum(bucket["http_reqs"] for bucket in buckets.values())
    if dropped != summary["dropped_iterations"] or requests != summary["http_requests"]:
        raise ValueError("1초 지표 합계와 k6 전체 요약이 다릅니다")
    if not any(bucket["vus"] for bucket in buckets.values()) or not any(
            bucket["vus_max"] for bucket in buckets.values()):
        raise ValueError("활성 또는 확보 VU 시계열이 없습니다")

    host = {}
    with host_path.open(newline="", encoding="utf-8") as source:
        for row in csv.DictReader(source):
            host[utc_second(row["at_utc"])] = row
    if len(host) < 2:
        raise ValueError("VM CPU·메모리 표본이 2개 미만입니다")

    with private_output(output_path) as destination:
        writer = csv.DictWriter(destination, fieldnames=CSV_FIELDS)
        writer.writeheader()
        for second in sorted(set(buckets) | set(host)):
            bucket = buckets[second]
            machine = host.get(second, {})
            writer.writerow(dict(zip(CSV_FIELDS, (
                second, int(bucket["dropped_iterations"]), int(bucket["http_reqs"]),
                max(bucket["vus"], default=""), max(bucket["vus_max"], default=""),
                p95(bucket["http_req_waiting"]), p95(bucket["http_req_blocked"]),
                p95(bucket["iteration_duration"]), machine.get("host_cpu_pct", ""),
                machine.get("mem_available_mib", ""), machine.get("process_cpu_pct", ""),
                machine.get("process_rss_mib", ""), machine.get("process_threads", ""),
            ))))
    return {"dropped": int(dropped), "requests": int(requests),
            "seconds": len(buckets), "host_seconds": len(host),
            "active_vu_peak": max(max(bucket["vus"], default=0) for bucket in buckets.values()),
            "allocated_vu_peak": max(max(bucket["vus_max"], default=0) for bucket in buckets.values())}


def main(argv=None):
    """Expose a sampler and a fail-closed per-run timeline join."""
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    sampler = actions.add_parser("sample")
    sampler.add_argument("--pid", type=int, required=True)
    sampler.add_argument("--out", type=Path, required=True)
    report = actions.add_parser("summarize")
    for name in ("metrics", "host", "summary", "out"):
        report.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.action == "sample":
        if args.pid <= 0:
            parser.error("PID가 올바르지 않습니다")
        sample(args.pid, args.out)
    else:
        print(json.dumps(summarize(args.metrics, args.host, args.summary, args.out),
                         ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
