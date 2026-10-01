#!/usr/bin/env python3
"""Run the dashboard k6 scenario while allowlisting samples before any log write."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone, timedelta
import json
import math
import os
from pathlib import Path
import re
import select
import subprocess


KST = timezone(timedelta(hours=9))
RUN_ID = re.compile(r"[a-zA-Z0-9_-]{4,64}\Z")
ALLOWED_METRICS = frozenset({
    "dashboard_connected", "dashboard_received", "dashboard_errors",
    "dashboard_negative_clock", "dashboard_sequence_gaps",
    "dashboard_duplicates", "dashboard_latency_ms",
})


def safe_point(line: bytes) -> dict | None:
    """Copy only timestamp, known metric name, and finite number; discard all tags."""
    try:
        source = json.loads(line)
        if source.get("type") != "Point" or source.get("metric") not in ALLOWED_METRICS:
            return None
        value = float(source["data"]["value"])
        if not math.isfinite(value):
            return None
        at = datetime.fromisoformat(source["data"]["time"].replace("Z", "+00:00"))
        return {
            "시각_KST": at.astimezone(KST).isoformat(),
            "지표": source["metric"],
            "값": value,
        }
    except (KeyError, TypeError, ValueError, json.JSONDecodeError):
        return None


def run(args: argparse.Namespace) -> int:
    if not RUN_ID.fullmatch(args.run_id) or args.clients not in (1, 5, 20, 50):
        raise ValueError("run-id 형식 또는 구독자 수가 허용 범위를 벗어났습니다.")
    if args.warmup_seconds < 0 or args.measure_seconds < 1:
        raise ValueError("예열·측정 시간은 음수가 아니고 측정 시간은 1초 이상이어야 합니다.")
    root = Path(args.output_dir).resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    fifo = root / "k6-stream.pipe"
    os.mkfifo(fifo, 0o600)
    samples = root / "samples.jsonl"
    summary = root / "summary.json"
    manifest = {
        "run_id": args.run_id,
        "목적": "대시보드 WebSocket 구독 부하",
        "실행위치": "GCP 내부 부하 VM",
        "변경판": args.variant,
        "구독자수": args.clients,
        "예열_초": args.warmup_seconds,
        "측정_초": args.measure_seconds,
        "시작_KST": datetime.now(KST).isoformat(),
        "명령": "k6 run --out json=<메모리 파이프> k6_dashboard_load.js",
        "성공조건": "연결 수 일치, 오류·음수 시계 차이 0건, summary와 개별 샘플 존재",
    }
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")

    env = os.environ.copy()
    env.update({
        "DASHBOARD_WS_URL": args.url,
        "DASHBOARD_TOKEN_FILE": args.token_file,
        "DASHBOARD_CLIENTS": str(args.clients),
        "DASHBOARD_WARMUP_SECONDS": str(args.warmup_seconds),
        "DASHBOARD_MEASURE_SECONDS": str(args.measure_seconds),
        "DASHBOARD_RUN_ID": args.run_id,
        "DASHBOARD_SUMMARY_FILE": str(summary),
    })
    command = ["k6", "run", "--no-color", "--out", f"json={fifo}", args.script]
    try:
        process = subprocess.Popen(command, env=env, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL)
    except OSError as error:
        fifo.unlink(missing_ok=True)
        manifest.update({"종료_KST": datetime.now(KST).isoformat(),
                         "오류종류": type(error).__name__, "k6_종료코드": None})
        (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"run_id": args.run_id, "outcome": "k6_start_failed"}))
        return 1
    points = 0
    discarded = 0
    buffer = b""
    try:
        # The FIFO never stores raw k6 tags/URLs. Read into memory, allowlist, then write.
        descriptor = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
        try:
            with samples.open("x", encoding="utf-8") as output:
                while process.poll() is None:
                    readable, _, _ = select.select([descriptor], [], [], 1.0)
                    if not readable:
                        continue
                    buffer += os.read(descriptor, 65536)
                    while b"\n" in buffer:
                        line, buffer = buffer.split(b"\n", 1)
                        record = safe_point(line)
                        if record is None:
                            discarded += 1
                        else:
                            output.write(json.dumps(record, ensure_ascii=False) + "\n")
                            output.flush()
                            points += 1
                # Drain the remaining points without waiting forever on our own FIFO writer end.
                while select.select([descriptor], [], [], 0.1)[0]:
                    try:
                        chunk = os.read(descriptor, 65536)
                    except BlockingIOError:
                        break
                    if not chunk:
                        break
                    buffer += chunk
                for line in buffer.splitlines():
                    record = safe_point(line)
                    if record is None:
                        discarded += 1
                    else:
                        output.write(json.dumps(record, ensure_ascii=False) + "\n")
                        points += 1
        finally:
            os.close(descriptor)
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        fifo.unlink(missing_ok=True)

    manifest.update({
        "종료_KST": datetime.now(KST).isoformat(),
        "k6_종료코드": process.wait(),
        "허용목록_샘플수": points,
        "제외한_원시행수": discarded,
        "요약파일_생성": summary.exists(),
    })
    (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"run_id": args.run_id, "exit_code": process.returncode,
                      "samples": points, "summary_exists": summary.exists()}, ensure_ascii=False))
    return 0 if process.returncode == 0 and points > 0 and summary.exists() else 1


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--variant", choices=("before", "after"), required=True)
    parser.add_argument("--clients", type=int, required=True)
    parser.add_argument("--warmup-seconds", type=int, default=10)
    parser.add_argument("--measure-seconds", type=int, default=30)
    parser.add_argument("--url", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--output-dir", required=True)
    parser.add_argument("--script", default="k6_dashboard_load.js")
    args = parser.parse_args()
    raise SystemExit(run(args))


if __name__ == "__main__":
    main()
