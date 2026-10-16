#!/usr/bin/env python3
"""Keep a Barman WAL receiver pointed at the elected OpenSQL primary.

This backup-host-only command reads the restricted Patroni metrics gateways and
switches an existing Barman configuration model. It never changes DB roles or
resets WAL history; a failed quorum or health check requires operator review.
"""

from __future__ import annotations

import argparse
import fcntl
from http.client import HTTPConnection, HTTPException
import ipaddress
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse


METRIC_PATTERN = re.compile(r"^(patroni_primary|patroni_postgres_running)(?:\{[^}]*\})?\s+([01](?:\.0)?)$")
MODEL_PATTERN = re.compile(r"^[A-Za-z0-9_.:-]+$")
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(value) for value in
                         ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))


def load_config(path: Path) -> dict:
    """Validate a local, private three-node configuration before contacting Barman."""
    config = json.loads(path.read_text())
    if not MODEL_PATTERN.fullmatch(config.get("server", "")):
        raise ValueError("invalid server")
    nodes = config.get("nodes")
    if not isinstance(nodes, list) or len(nodes) != 3:
        raise ValueError("exactly three nodes required")
    models = set()
    hosts = set()
    for node in nodes:
        model = node.get("model", "")
        parsed = urlparse(node.get("metrics_url", ""))
        if not MODEL_PATTERN.fullmatch(model) or model in models:
            raise ValueError("invalid or duplicate model")
        if parsed.scheme != "http" or parsed.port != 18008 or parsed.path != "/metrics" or parsed.query:
            raise ValueError("invalid metrics endpoint")
        host = ipaddress.ip_address(parsed.hostname or "")
        if host.version != 4 or not any(host in network for network in PRIVATE_NETWORKS) or str(host) in hosts:
            raise ValueError("invalid or duplicate private host")
        if parsed.username or parsed.password or parsed.fragment:
            raise ValueError("metrics endpoint must not contain credentials")
        models.add(model)
        hosts.add(str(host))
    return config


def read_role(url: str) -> tuple[int, int]:
    """Accept exactly one sample for each required metric from a bounded response."""
    parsed = urlparse(url)
    connection = HTTPConnection(parsed.hostname, parsed.port, timeout=2)
    try:
        connection.request("GET", "/metrics")
        response = connection.getresponse()
        if response.status != 200:
            raise ValueError("metrics unavailable")
        body = response.read(65537)
    finally:
        connection.close()
    if len(body) > 65536:
        raise ValueError("metrics too large")
    values: dict[str, int] = {}
    for line in body.decode("utf-8").splitlines():
        match = METRIC_PATTERN.fullmatch(line.strip())
        if match:
            if match.group(1) in values:
                raise ValueError("duplicate role sample")
            values[match.group(1)] = int(float(match.group(2)))
    if set(values) != {"patroni_primary", "patroni_postgres_running"}:
        raise ValueError("incomplete role metrics")
    return values["patroni_primary"], values["patroni_postgres_running"]


def elect_primary(nodes: list[dict]) -> tuple[int, int]:
    """Require two healthy observations and exactly one running primary."""
    observations = []
    for index, node in enumerate(nodes):
        try:
            primary, running = read_role(node["metrics_url"])
            observations.append((index, primary, running))
        except (OSError, HTTPException, ValueError, UnicodeError):
            continue
    if sum(running == 1 for _, _, running in observations) < 2:
        raise ValueError("role quorum unavailable")
    leaders = [index for index, primary, running in observations if primary == 1 and running == 1]
    if len(leaders) != 1 or any(primary == 1 and running == 0 for _, primary, running in observations):
        raise ValueError("ambiguous primary")
    return leaders[0], len(observations)


def run_barman(*arguments: str) -> subprocess.CompletedProcess[str]:
    """Capture Barman output locally so connection strings never reach journals."""
    return subprocess.run(["barman", *arguments], text=True, capture_output=True, timeout=40, check=False)


def effective_settings(server: str) -> dict[str, str]:
    """Read only the three fields needed for idempotence and post-switch checks."""
    result = run_barman("show-servers", server)
    if result.returncode != 0:
        raise ValueError("barman configuration read failed")
    settings = {}
    for line in result.stdout.splitlines():
        if ":" not in line:
            continue
        key, value = line.strip().split(":", 1)
        if key in {"active_model", "conninfo", "streaming_conninfo"}:
            settings[key] = value.strip()
    if set(settings) != {"active_model", "conninfo", "streaming_conninfo"}:
        raise ValueError("barman configuration fields missing")
    return settings


def connection_host(conninfo: str) -> str:
    """Extract a single host without logging any other connection parameters."""
    pairs = dict(part.split("=", 1) for part in shlex.split(conninfo) if "=" in part)
    host = pairs.get("host") or pairs.get("hostaddr")
    if not host or "," in host:
        raise ValueError("barman connection must have one host")
    return host


def matches(settings: dict[str, str], model: str, host: str) -> bool:
    """Avoid restarting the receiver when its model and both targets are current."""
    return (settings["active_model"] == model
            and connection_host(settings["conninfo"]) == host
            and connection_host(settings["streaming_conninfo"]) == host)


def require_success(*arguments: str) -> None:
    """Fail closed on a Barman mutation without printing its captured output."""
    if run_barman(*arguments).returncode != 0:
        raise ValueError("barman command failed: " + arguments[0])


def receiver_healthy(server: str) -> bool:
    """Check the streaming and receiver checks, not unrelated retention checks."""
    result = run_barman("check", server)
    statuses = {}
    for line in result.stdout.splitlines():
        if ":" in line:
            key, value = line.strip().split(":", 1)
            statuses[key.lower()] = value.strip().split(" ", 1)[0].upper()
    return (statuses.get("postgresql streaming") == "OK"
            and statuses.get("receive-wal running") == "OK")


def follow(config: dict, dry_run: bool) -> dict:
    """Switch once for a quorum-backed role change, then verify the receiver."""
    nodes = config["nodes"]
    index, observed = elect_primary(nodes)
    selected = nodes[index]
    host = urlparse(selected["metrics_url"]).hostname
    server = config["server"]
    before = effective_settings(server)
    aligned = matches(before, selected["model"], host)
    if dry_run:
        return {"status": "aligned" if aligned else "switch_required", "observed": observed, "primary_index": index + 1}
    if not aligned:
        old_target = (connection_host(before["conninfo"]), connection_host(before["streaming_conninfo"]))
        # 1. Validate that Barman can apply the model before interrupting WAL intake.
        require_success("config-switch", server, selected["model"])
        if not matches(effective_settings(server), selected["model"], host):
            raise ValueError("barman target verification failed")
        # 2. A model applied to the same host needs no receiver interruption.
        if old_target != (host, host):
            require_success("receive-wal", "--stop", server)
    # 3. Barman cron starts or repairs the streaming receiver after a restart.
    require_success("cron")
    for attempt in range(5):
        if receiver_healthy(server):
            return {"status": "healthy", "switched": not aligned, "observed": observed, "primary_index": index + 1}
        if attempt < 4:
            time.sleep(3)
    raise ValueError("wal receiver health check failed")


def main() -> int:
    """Serialize timer runs and emit only redacted, structured outcomes."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--lock", type=Path, default=Path("/var/lib/barman/wal-follow.lock"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        config = load_config(args.config)
        with args.lock.open("a+") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            print(json.dumps(follow(config, args.dry_run), sort_keys=True))
        return 0
    except (OSError, ValueError, json.JSONDecodeError, subprocess.TimeoutExpired) as error:
        # Error text is deliberately omitted: subprocess output and URLs may be sensitive.
        print(json.dumps({"status": "failed", "reason": type(error).__name__}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
