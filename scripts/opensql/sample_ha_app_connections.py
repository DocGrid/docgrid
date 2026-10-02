#!/usr/bin/env python3
"""Sample one DocGrid JVM's proxy sockets and Hikari counters without logging endpoints."""

from __future__ import annotations

import argparse
import csv
import ipaddress
import os
import re
import socket
import subprocess
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


RUN_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,59}\Z")
SOCKET_FD = re.compile(r"socket:\[(\d+)\]\Z")
METRICS_URL = "http://127.0.0.1:8081/actuator/prometheus"
METRIC_NAMES = {
    "hikaricp_connections_active": "hikari_active",
    "hikaricp_connections_idle": "hikari_idle",
    "hikaricp_connections_pending": "hikari_pending",
    "hikaricp_connections": "hikari_total",
    "hikaricp_connections_timeout_total": "hikari_timeout_total",
}
FIELDS = (
    "at_utc", "role", "proxy_a_established", "proxy_b_established",
    "proxy_a_new_cumulative", "proxy_b_new_cumulative", "hikari_active",
    "hikari_idle", "hikari_pending", "hikari_total", "hikari_timeout_total",
    "probe_201_total", "metrics_ok",
)


def proxy_targets(env_path: Path) -> dict[str, tuple[str, int]]:
    """Read the private JDBC URL in memory and return two endpoints without printing them."""
    matches = [line.split("=", 1)[1].strip().strip("\"'")
               for line in env_path.read_text(encoding="utf-8").splitlines()
               if line.startswith("OPENSQL_APP_JDBC_URL=")]
    if len(matches) != 1 or not matches[0].startswith("jdbc:postgresql://"):
        raise ValueError("Expected exactly one OpenSQL application JDBC URL")
    hosts = matches[0].removeprefix("jdbc:postgresql://").split("/", 1)[0].split(",")
    if len(hosts) != 2:
        raise ValueError("Expected exactly two proxy endpoints")
    endpoints = []
    for host in hosts:
        address, separator, port = host.rpartition(":")
        if not separator or port != "6432":
            raise ValueError("Expected proxy port 6432")
        endpoints.append((str(ipaddress.IPv4Address(address)), int(port)))
    if endpoints[0] == endpoints[1]:
        raise ValueError("Proxy endpoints must differ")
    return dict(zip(("proxy_a", "proxy_b"), endpoints))


def java_main_pid() -> int:
    """Bind samples to the actual systemd-managed JVM, not unrelated host sockets."""
    result = subprocess.run(
        ["systemctl", "show", "docgrid", "--property=MainPID", "--value"],
        check=True, capture_output=True, text=True,
    )
    pid = int(result.stdout.strip())
    if pid < 2 or Path(f"/proc/{pid}/comm").read_text().strip() != "java":
        raise ValueError("DocGrid systemd MainPID is not a running Java process")
    return pid


def java_socket_inodes(pid: int) -> set[str]:
    """Collect only socket inodes owned by the selected Java process."""
    inodes = set()
    for fd in Path(f"/proc/{pid}/fd").iterdir():
        try:
            match = SOCKET_FD.fullmatch(os.readlink(fd))
        except (FileNotFoundError, PermissionError):
            continue
        if match:
            inodes.add(match.group(1))
    return inodes


def match_proxy_tcp_rows(lines: list[str], owned: set[str],
                         targets: dict[str, tuple[str, int]], *, ipv6: bool = False
                         ) -> dict[str, set[str]]:
    """Ignore other processes and retain only established sockets to known proxies."""
    matched = {name: set() for name in targets}
    for line in lines[1:]:
        fields = line.split()
        if len(fields) < 10 or fields[3] != "01" or fields[9] not in owned:
            continue
        address_hex, port_hex = fields[2].split(":")
        raw_address = bytes.fromhex(address_hex)
        if ipv6:
            # Linux procfs prints IPv6 addresses as four little-endian words.
            network_address = b"".join(raw_address[i:i + 4][::-1] for i in range(0, 16, 4))
            mapped = ipaddress.IPv6Address(network_address).ipv4_mapped
            if mapped is None:
                continue
            remote_address = str(mapped)
        else:
            remote_address = socket.inet_ntoa(raw_address[::-1])
        remote = (remote_address, int(port_hex, 16))
        for name, endpoint in targets.items():
            if remote == endpoint:
                matched[name].add(fields[9])
    return matched


def established_proxy_inodes(pid: int, targets: dict[str, tuple[str, int]]) -> dict[str, set[str]]:
    """Match JVM-owned ESTABLISHED IPv4 and IPv4-mapped TCP6 sockets to JDBC proxies."""
    owned = java_socket_inodes(pid)
    matched = {name: set() for name in targets}
    for source, ipv6 in (("tcp", False), ("tcp6", True)):
        current = match_proxy_tcp_rows(
            Path(f"/proc/{pid}/net/{source}").read_text().splitlines(),
            owned, targets, ipv6=ipv6,
        )
        for name in targets:
            matched[name].update(current[name])
    return matched


def metric_values() -> dict[str, float]:
    """Discard all labels and expose only known numeric metrics from loopback."""
    with urllib.request.urlopen(METRICS_URL, timeout=2) as response:
        lines = response.read(2_000_000).decode("utf-8").splitlines()
    values = {name: 0.0 for name in METRIC_NAMES.values()}
    values["probe_201_total"] = 0.0
    for line in lines:
        if not line or line.startswith("#"):
            continue
        name = line.split("{", 1)[0].split(" ", 1)[0]
        if name in METRIC_NAMES:
            values[METRIC_NAMES[name]] += float(line.rsplit(" ", 1)[1])
        elif name == "http_server_requests_seconds_count" and \
                'uri="/api/ha-probe/writes"' in line and 'status="201"' in line:
            values["probe_201_total"] += float(line.rsplit(" ", 1)[1])
    return values


def run(args: argparse.Namespace) -> int:
    """Write a bounded, owner-only and fsynced numeric CSV for one app instance."""
    if not RUN_ID.fullmatch(args.run_id) or not 10 <= args.duration_seconds <= 300 or \
            args.interval_ms not in (250, 500, 1000) or args.output.exists():
        raise ValueError("Invalid or reused sampler run")
    targets = proxy_targets(args.env_file)
    pid = java_main_pid()
    baseline = established_proxy_inodes(pid, targets)
    seen_new = {name: set() for name in targets}
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    samples = 0
    missing_metrics = 0
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        started = time.monotonic()
        next_tick = started
        while time.monotonic() - started < args.duration_seconds:
            # 1. A restart invalidates the per-JVM socket baseline; never merge two processes.
            if Path(f"/proc/{pid}/comm").read_text().strip() != "java":
                raise RuntimeError("The sampled JVM exited")
            current = established_proxy_inodes(pid, targets)
            for name in targets:
                seen_new[name].update(current[name] - baseline[name])
            # 2. Keep a failed metrics scrape explicit rather than turning absence into zero.
            try:
                metrics = metric_values()
                metrics_ok = 1
            except (OSError, UnicodeError, ValueError):
                metrics = {name: -1 for name in METRIC_NAMES.values()}
                metrics["probe_201_total"] = -1
                metrics_ok = 0
                missing_metrics += 1
            row = {
                "at_utc": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                "role": args.role,
                "proxy_a_established": len(current["proxy_a"]),
                "proxy_b_established": len(current["proxy_b"]),
                "proxy_a_new_cumulative": len(seen_new["proxy_a"]),
                "proxy_b_new_cumulative": len(seen_new["proxy_b"]),
                **metrics, "metrics_ok": metrics_ok,
            }
            # 3. Persist each sample during the test so a later interruption is visible.
            writer.writerow(row)
            output.flush()
            os.fsync(output.fileno())
            samples += 1
            next_tick += args.interval_ms / 1000
            time.sleep(max(0, next_tick - time.monotonic()))
    print(f"samples={samples} missing_metrics={missing_metrics} role={args.role}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--role", choices=("app-a", "app-b"), required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--interval-ms", type=int, default=500)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        return run(args)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError):
        print("CONNECTION_SAMPLER_FAILED")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
