#!/usr/bin/env python3
"""Start two isolated local DocGrid apps and run the permission lag experiment.

The private env file is read as Java properties, never sourced by a shell.
Application logs remain owner-only in the private output directory.
"""

from __future__ import annotations

import argparse
import hashlib
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/opensql/permission_replica_lag.py"


def load_env(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.isidentifier():
            raise ValueError("Invalid key in private env file")
        values[key] = value.strip().strip('"').strip("'")
    return values


def port_listening(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def start_app(jar: Path, env: dict[str, str], port: int, log_path: Path) -> subprocess.Popen:
    # 1. Each JVM has its own Hikari pool; only the direct instance overrides the URL.
    log = log_path.open("x")
    log_path.chmod(0o600)
    try:
        process = subprocess.Popen([
            "java", "-jar", str(jar), f"--server.port={port}",
            "--server.address=127.0.0.1", "--management.server.address=127.0.0.1",
            "--spring.profiles.active=opensql-ha", "--spring.flyway.enabled=false",
            "--management.server.port=0", "--indexing.worker.enabled=false",
            "--sync.dispatcher.enabled=false", "--sync.reconciliation.enabled=false",
        ], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
            start_new_session=True)
    finally:
        log.close()
    return process


def await_port(process: subprocess.Popen, port: int) -> None:
    for _ in range(90):
        if process.poll() is not None:
            raise RuntimeError("A test app exited during startup; inspect its private log")
        if port_listening(port):
            return
        time.sleep(1)
    raise RuntimeError("A test app did not open its port; inspect its private log")


def stop_app(process: subprocess.Popen) -> None:
    # 2. Stop only the two child JVMs started by this wrapper.
    if process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--redis-port", type=int, default=16379,
                        help="Port of a disposable, isolated loopback Redis instance")
    parser.add_argument("--apply-delay", action="store_true",
                        help="Run the guarded standby apply-delay phase after local apps start")
    args = parser.parse_args()
    if args.output.exists() or any(port_listening(port) for port in (18080, 18081)):
        print("Output directory or test app port already exists", file=sys.stderr)
        return 2
    # Non-secret route overrides may come from the process; the private file wins on conflicts.
    values = os.environ | load_env(args.env_file)
    required = ("OPENSQL_APP_JDBC_URL", "OPENSQL_APP_DIRECT_JDBC_URL",
                "OPENSQL_APP_USER", "OPENSQL_APP_PASSWORD", "JWT_SECRET")
    if any(not values.get(key) for key in required):
        print("Private env file is missing a required setting", file=sys.stderr)
        return 2
    args.output.mkdir(parents=True, mode=0o700)
    if not 1 <= args.redis_port <= 65535:
        print("Invalid isolated Redis port", file=sys.stderr)
        return 2
    base_env = values | {"REDIS_HOST": "127.0.0.1", "REDIS_PORT": str(args.redis_port)}
    children = []
    try:
        proxy = start_app(args.jar, base_env, 18080, args.output / "proxy-app.log")
        children.append(proxy)
        await_port(proxy, 18080)
        direct_env = base_env | {"OPENSQL_APP_JDBC_URL": values["OPENSQL_APP_DIRECT_JDBC_URL"]}
        direct = start_app(args.jar, direct_env, 18081, args.output / "primary-app.log")
        children.append(direct)
        await_port(direct, 18081)
        # 3. The runner owns the remote guard, HTTP requests, evidence, and cleanup.
        runner_args = [
            sys.executable, str(RUNNER), "--proxy-url", "http://127.0.0.1:18080",
            "--primary-url", "http://127.0.0.1:18081", "--output", str(args.output),
            "--app-jar-sha256", file_sha256(args.jar),
        ]
        if args.apply_delay:
            runner_args.append("--apply-delay")
        result = subprocess.run(runner_args, cwd=ROOT, env=base_env, check=False)
        return result.returncode
    except (OSError, RuntimeError) as error:
        print(f"Local app setup stopped: {error}", file=sys.stderr)
        return 1
    finally:
        for child in reversed(children):
            stop_app(child)


if __name__ == "__main__":
    sys.exit(main())
