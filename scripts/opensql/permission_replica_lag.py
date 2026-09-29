#!/usr/bin/env python3
"""Run the real HTTP permission path against the three-node OpenSQL cluster.

This is an opt-in, one-shot experiment, not a production service or an automated
CI test. The VM-host rescue timers are independent of this process and SSH.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / "scripts/opensql/ha_evidence.py"
CONTRACT = ROOT / "docs/test-results/opensql-contract-evidence/contract-manifest.json"
NODES = ("docgrid-node1", "docgrid-node2", "docgrid-node3")
STANDBYS = NODES[1:]
LABEL = re.compile(r"[a-z0-9][a-z0-9-]{0,23}\Z")
DELAY_SECONDS = 120
GUARD_SECONDS = 240


class InvalidExperiment(RuntimeError):
    """Stop a run whose preconditions or evidence cannot support a conclusion."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def required(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise InvalidExperiment(f"Missing {name}")
    return value


def command(argv: list[str], *, timeout: int = 30) -> str:
    result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # Do not copy remote stderr: client exceptions may contain endpoint details.
        raise InvalidExperiment(f"Command failed: {argv[0]} {argv[1]} (exit {result.returncode})")
    return result.stdout.strip()


def remote(node: str, script: str, *args: str) -> str:
    if node not in NODES or script not in {"docgrid-permission-node-probe",
                                          "docgrid-standby-apply-delay-guard", "docgrid-permission-fixture"}:
        raise InvalidExperiment("Unexpected remote target")
    for value in args:
        if not re.fullmatch(r"[a-z0-9-]+", value):
            raise InvalidExperiment("Unsafe remote argument")
    return command(["gcloud", "compute", "ssh", node,
                    f"--zone={required('OPENSQL_GCP_ZONE')}",
                    f"--project={required('OPENSQL_EXPECTED_PROJECT')}",
                    f"--ssh-key-file={required('OPENSQL_SSH_KEY')}", "--quiet",
                    f"--command=sudo /usr/local/sbin/{script} {' '.join(args)}"], timeout=35)


def check_target() -> None:
    account = command(["gcloud", "auth", "list", "--filter=status:ACTIVE",
                       "--format=value(account)"])
    project = command(["gcloud", "config", "get-value", "project"])
    if account != required("OPENSQL_EXPECTED_ACCOUNT") or project != required("OPENSQL_EXPECTED_PROJECT"):
        raise InvalidExperiment("Active GCP account or project differs from approved target")


def redis(*parts: str) -> object:
    host = os.environ.get("REDIS_HOST", "127.0.0.1")
    port = int(os.environ.get("REDIS_PORT", "6379"))
    if host not in {"localhost", "127.0.0.1"}:
        raise InvalidExperiment("This local experiment requires loopback Redis")
    commands = []
    password = os.environ.get("REDIS_PASSWORD", "")
    if password:
        commands.append(("AUTH", password))
    commands.append(parts)
    with socket.create_connection((host, port), timeout=2) as connection:
        connection.settimeout(2)
        stream = connection.makefile("rb")
        result = None
        for values in commands:
            payload = f"*{len(values)}\r\n".encode()
            for value in values:
                data = str(value).encode()
                payload += f"${len(data)}\r\n".encode() + data + b"\r\n"
            connection.sendall(payload)
            prefix = stream.read(1)
            line = stream.readline().removesuffix(b"\r\n")
            if prefix == b"-":
                raise InvalidExperiment("Redis command failed")
            if prefix == b"$":
                length = int(line)
                result = None if length == -1 else stream.read(length).decode()
                if length != -1:
                    stream.read(2)
            elif prefix == b":":
                result = int(line)
            elif prefix == b"+":
                result = line.decode()
            else:
                raise InvalidExperiment("Unexpected Redis response")
        return result


def cache(user_id: int) -> dict[str, object]:
    key = f"auth:roles:{user_id}"
    value = redis("GET", key)
    return {"present": value is not None, "admin": value is not None and
            "ADMIN" in value.split(","), "pttl_ms": redis("PTTL", key)}


def token(user_id: int, run_id: str) -> str:
    secret = required("JWT_SECRET").encode()
    if len(secret) < 32:
        raise InvalidExperiment("JWT secret is too short")
    algorithm, digest = (("HS512", hashlib.sha512) if len(secret) >= 64 else
                         ("HS384", hashlib.sha384) if len(secret) >= 48 else
                         ("HS256", hashlib.sha256))

    def encoded(value: dict[str, object]) -> str:
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")

    now = int(time.time())
    parts = [encoded({"alg": algorithm, "typ": "JWT"}),
             encoded({"sub": f"ha-{run_id}@invalid.example", "userId": user_id,
                      "jti": str(uuid.uuid4()), "iat": now, "exp": now + 600})]
    signature = hmac.new(secret, ".".join(parts).encode(), digest).digest()
    return ".".join(parts + [base64.urlsafe_b64encode(signature).decode().rstrip("=")])


def ledger(run_dir: Path, action: str, *args: str) -> str:
    return command([sys.executable, str(EVIDENCE), action, "--run-dir", str(run_dir), *args])


def http(run_dir: Path, base_url: str, path: str, jwt: str, operation: str,
         method: str = "GET") -> int:
    request_id = ledger(run_dir, "sent", "--operation", operation)
    request = urllib.request.Request(base_url + path, method=method,
                                     headers={"Authorization": "Bearer " + jwt})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    except (urllib.error.URLError, TimeoutError):
        ledger(run_dir, "unknown", "--request-id", request_id, "--reason", "connection_lost")
        raise InvalidExperiment("HTTP outcome unknown") from None
    ledger(run_dir, "ack" if 200 <= status < 300 else "fail",
           "--request-id", request_id, "--http-status", str(status))
    return status


def role_stats() -> dict[str, int]:
    with ThreadPoolExecutor(max_workers=3) as pool:
        rows_by_node = dict(zip(NODES, pool.map(
            lambda node: remote(node, "docgrid-permission-node-probe", "role-stats", node), NODES)))
    return {node: sum(int(row.split(",")[1]) for row in rows.splitlines() if row)
            for node, rows in rows_by_node.items()}


def deltas(before: dict[str, int], after: dict[str, int]) -> dict[str, int]:
    delta = {node: after[node] - before[node] for node in NODES}
    if any(value < 0 for value in delta.values()):
        raise InvalidExperiment("pg_stat_statements counters reset during the run")
    return delta


def roles(user_id: int) -> dict[str, bool]:
    with ThreadPoolExecutor(max_workers=3) as pool:
        values = pool.map(lambda node: remote(node, "docgrid-permission-node-probe",
                                              "role", node, str(user_id)), NODES)
        return {node: value == "t" for node, value in zip(NODES, values)}


def lsn() -> dict[str, dict[str, str]]:
    with ThreadPoolExecutor(max_workers=3) as pool:
        values = pool.map(lambda node: remote(node, "docgrid-permission-node-probe",
                                              "lsn", node), NODES)
        return {node: dict(zip(("in_recovery", "receive_lsn", "replay_lsn"),
                               value.split(","), strict=True))
                for node, value in zip(NODES, values)}


def status(run_id: str) -> dict[str, str]:
    with ThreadPoolExecutor(max_workers=2) as pool:
        values = pool.map(lambda node: remote(node, "docgrid-standby-apply-delay-guard",
                                              "status", node, run_id), STANDBYS)
        return dict(zip(STANDBYS, values))


def restore_delay(run_id: str) -> None:
    def attempt(node: str) -> str | None:
        try:
            remote(node, "docgrid-standby-apply-delay-guard", "restore", node, run_id,
                   str(GUARD_SECONDS), str(DELAY_SECONDS))
            return None
        except Exception:
            return node

    with ThreadPoolExecutor(max_workers=2) as pool:
        errors = [node for node in pool.map(attempt, STANDBYS) if node]
    if errors:
        raise InvalidExperiment("Delay reset failed on " + ", ".join(errors))


def fixture(run_id: str, action: str) -> dict[str, int]:
    output = remote(NODES[0], "docgrid-permission-fixture", action, NODES[0], run_id)
    if action != "create":
        return {}
    ids = {}
    for row in output.splitlines():
        alias, user_id = row.split(",")
        ids[alias.removeprefix(f"ha-{run_id}-")] = int(user_id)
    if set(ids) != {"admin", "m", "c1", "c2"}:
        raise InvalidExperiment("Fixture returned an incomplete user set")
    return ids


def require_status(actual: int, expected: int, label: str) -> None:
    if actual != expected:
        raise InvalidExperiment(f"{label}: expected HTTP {expected}, received {actual}")


def run(args: argparse.Namespace) -> None:
    check_target()
    redis("PING")
    if not args.proxy_url.startswith("http://127.0.0.1:") or not args.primary_url.startswith("http://127.0.0.1:"):
        raise InvalidExperiment("Both test app instances must be on loopback")
    if args.proxy_url == args.primary_url:
        raise InvalidExperiment("Proxy and direct-primary app URLs must differ")
    contract = json.loads(CONTRACT.read_text())
    versions = contract["snapshot"]["nodes"][0]["versions"]
    run_id = uuid.uuid4().hex[:12]
    if not LABEL.fullmatch(run_id):
        raise InvalidExperiment("Invalid generated run ID")
    for node in NODES:
        lines = remote(node, "docgrid-permission-node-probe", "health", node).splitlines()
        expected = "f,t,2,0" if node == NODES[0] else "t,t,0,1"
        if len(lines) != 2 or lines[0] != expected:
            raise InvalidExperiment("Expected one primary, two streaming standbys, "
                                    "and postgres-DB statistics extension on " + node)
    initial = status(run_id)
    if any("streaming=true" not in line or "delay=0,default" not in line for line in initial.values()):
        raise InvalidExperiment("Standby not streaming or already delayed")

    # 1. This manifest refers to the redacted contract snapshot; no URL or secret is stored.
    config_hash = contract["evidence_sha256"]
    run_dir = args.output / f"permission-{run_id}"
    ledger(run_dir, "init", "--scenario", "permission-replica-lag",
           "--config-sha256", config_hash,
           "--opensql-version", versions["opensql"],
           "--openproxy-version", "recorded-in-contract",
           "--patroni-version", versions["patroni"],
           "--etcd-version", versions["etcd"])
    evidence: dict[str, object] = {"run_id": run_id, "started_at": utc_now(),
                                   "contract_sha256": config_hash,
                                   "initial_standbys": initial, "initial_lsn": lsn()}
    ids: dict[str, int] = {}
    fixture_attempted = False
    armed = False
    delayed = False
    fault_open = False
    try:
        fixture_attempted = True
        ids = fixture(run_id, "create")
        jwt = {alias: token(user_id, run_id) for alias, user_id in ids.items()}
        for alias in ("admin", "m", "c1", "c2"):
            require_status(http(run_dir, args.proxy_url, "/admin/workers", jwt[alias],
                                f"baseline-{alias}"), 200, f"baseline {alias}")
        for alias in ("m", "c1", "c2"):
            if not all(roles(ids[alias]).values()):
                raise InvalidExperiment("Fixture ADMIN role has not reached every standby")

        # 2. Each R request is a genuine cache miss; cumulative node statistics show arrival.
        baseline = role_stats()
        for index in range(6):
            redis("DEL", f"auth:roles:{ids['m']}")
            require_status(http(run_dir, args.proxy_url, "/admin/workers", jwt["m"],
                                f"routing-{index}"), 200, "routing baseline")
        route = deltas(baseline, role_stats())
        evidence["R"] = {"role_sql_delta": route, "cache": cache(ids["m"])}
        if route[NODES[1]] + route[NODES[2]] == 0:
            raise InvalidExperiment("Role SQL did not reach standby; replay pause is prohibited")
        if not args.apply_delay:
            raise InvalidExperiment("R recorded; pass --apply-delay only after the independent "
                                    "reset timer and one-standby delay have been verified")

        # 3. Arm both independent VM-host reset timers before delaying either standby.
        armed = True
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda node: remote(node, "docgrid-standby-apply-delay-guard",
                                              "arm", node, run_id, str(GUARD_SECONDS),
                                              str(DELAY_SECONDS)), STANDBYS))
        guard_deadline = time.monotonic() + GUARD_SECONDS - 30
        if any("armed=true" not in line for line in status(run_id).values()):
            raise InvalidExperiment("An independent rescue timer is not pending")
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda node: remote(node, "docgrid-standby-apply-delay-guard",
                                              "apply", node, run_id, str(GUARD_SECONDS),
                                              str(DELAY_SECONDS)), STANDBYS))
        delayed = True
        before_revoke = status(run_id)
        if any(f"delay={DELAY_SECONDS * 1000},configuration file" not in line
               for line in before_revoke.values()):
            raise InvalidExperiment("Both standbys did not retain the effective apply delay")
        ledger(run_dir, "fault", "--name", "standby-apply-delay", "--phase", "start")
        fault_open = True

        # 4. Commit role revocation through the actual administrator HTTP API.
        for alias in ("m", "c2"):
            require_status(http(run_dir, args.proxy_url, f"/admin/users/{ids[alias]}/roles/ADMIN",
                                jwt["admin"], f"revoke-{alias}", "DELETE"), 200,
                           f"revoke {alias}")
        m_roles, c2_roles = roles(ids["m"]), roles(ids["c2"])
        if m_roles != {NODES[0]: False, NODES[1]: True, NODES[2]: True} or \
                c2_roles != {NODES[0]: False, NODES[1]: True, NODES[2]: True}:
            raise InvalidExperiment("Revocation did not produce the required primary/standby split")
        if cache(ids["m"])["present"] or cache(ids["c2"])["present"]:
            raise InvalidExperiment("Revoked users' Redis keys were not invalidated")
        evidence["after_revocation_lsn"] = lsn()
        if any(f"delay={DELAY_SECONDS * 1000},configuration file" not in line
               for line in status(run_id).values()):
            raise InvalidExperiment("A standby lost its delay before stale-read observation")

        if time.monotonic() >= guard_deadline:
            raise InvalidExperiment("Independent rescue timer is near its deadline")
        m_before = role_stats()
        m_http = http(run_dir, args.proxy_url, "/admin/workers", jwt["m"], "stale-M")
        m_after = role_stats()
        m_delta = deltas(m_before, m_after)
        m_cache = cache(ids["m"])
        c2_http = http(run_dir, args.primary_url, "/admin/workers", jwt["c2"], "primary-C2")
        c2_delta = deltas(m_after, role_stats())
        c2_cache = cache(ids["c2"])
        evidence["M"] = {"http_status": m_http, "role_sql_delta": m_delta,
                         "cache": m_cache, "roles": m_roles}
        evidence["C2"] = {"http_status": c2_http, "cache": c2_cache,
                          "roles": c2_roles, "role_sql_delta": c2_delta}
        evidence["delayed_standbys"] = status(run_id)
        evidence["delayed_lsn"] = lsn()
        if any(f"delay={DELAY_SECONDS * 1000},configuration file" not in line
               for line in evidence["delayed_standbys"].values()):
            raise InvalidExperiment("Standby apply delay ended during observation")

        # 5. Restore the normal apply policy immediately after the stale-read observation.
        restore_delay(run_id)
        delayed = False
        ledger(run_dir, "fault", "--name", "standby-apply-delay", "--phase", "end")
        fault_open = False
        for _ in range(20):
            if all(not value for value in roles(ids["m"]).values()):
                break
            time.sleep(1)
        if any(roles(ids["m"]).values()):
            raise InvalidExperiment("Replication did not catch up after replay resumed")
        redis("DEL", f"auth:roles:{ids['c1']}")
        require_status(http(run_dir, args.proxy_url, f"/admin/users/{ids['c1']}/roles/ADMIN",
                            jwt["admin"], "revoke-C1", "DELETE"), 200, "revoke C1")
        for _ in range(20):
            if not any(roles(ids["c1"]).values()):
                break
            time.sleep(1)
        if any(roles(ids["c1"]).values()):
            raise InvalidExperiment("C1 did not replicate before its fresh-read check")
        c1_http = http(run_dir, args.proxy_url, "/admin/workers", jwt["c1"], "fresh-C1")
        evidence["C1"] = {"http_status": c1_http, "cache": cache(ids["c1"]),
                          "roles": roles(ids["c1"])}
        if c2_delta[NODES[0]] == 0 or c2_delta[NODES[1]] + c2_delta[NODES[2]] != 0:
            raise InvalidExperiment("C2 did not prove direct-primary role lookup")
        evidence["result"] = ("STALE_ADMIN_ALLOWED" if m_http == 200 and m_cache["admin"]
                              and m_delta[NODES[1]] + m_delta[NODES[2]] > 0 and c1_http == 403
                              and c2_http == 403 else "DENIED" if m_http == 403 and
                              c1_http == 403 and c2_http == 403 else "INVALID")
    except Exception as error:
        evidence["result"] = "INVALID"
        evidence["stop_reason"] = str(error) if isinstance(error, InvalidExperiment) else type(error).__name__
        raise
    finally:
        # 6. The independent timers remain armed until every standby is visibly normal.
        if armed:
            try:
                restore_delay(run_id)
                delayed = False
                if fault_open:
                    ledger(run_dir, "fault", "--name", "standby-apply-delay", "--phase", "end")
                    fault_open = False
                for node in STANDBYS:
                    remote(node, "docgrid-standby-apply-delay-guard", "cancel", node, run_id)
            except Exception:
                evidence["cleanup_warning"] = "Manual standby inspection required"
        if fixture_attempted:
            try:
                for user_id in ids.values():
                    redis("DEL", f"auth:roles:{user_id}")
                fixture(run_id, "remove")
            except Exception:
                evidence["fixture_warning"] = "Manual fixture cleanup required"
        evidence["finished_at"] = utc_now()
        try:
            evidence["final_standbys"] = status(run_id)
            evidence["final_lsn"] = lsn()
        except Exception:
            evidence["final_standbys"] = "unverified; manual inspection required"
            evidence["result"] = "INVALID"
        if delayed:
            evidence["result"] = "INVALID"
            evidence["cleanup_warning"] = "Apply delay may still be active; inspect both standbys"
        path = run_dir / "permission-scenarios.json"
        path.write_text(json.dumps(evidence, ensure_ascii=False, sort_keys=True, indent=2) + "\n")
        path.chmod(0o600)
        ledger(run_dir, "export" if fault_open else "finish")
        print(f"run_dir={run_dir} result={evidence['result']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy-url", required=True)
    parser.add_argument("--primary-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply-delay", action="store_true",
                        help="Apply a temporary standby delay after independent reset verification")
    args = parser.parse_args()
    try:
        run(args)
    except (InvalidExperiment, OSError, subprocess.TimeoutExpired, ValueError) as error:
        print(f"Experiment stopped: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
