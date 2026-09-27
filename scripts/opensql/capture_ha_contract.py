#!/usr/bin/env python3
"""Collect only allowlisted OpenSQL HA settings and assemble a public-safe fingerprint."""

from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import io
import json
import os
import platform
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


NODE = re.compile(r"node[123]\Z")
PRIVATE_VALUE = re.compile(r"(?:\b\d{1,3}(?:\.\d{1,3}){3}\b|@|password|secret|token|license)", re.I)
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9 ._+():-]{0,127}\Z")
PROXY_FIELDS = {
    "general": {"prepared_statements_cache_size", "worker_threads", "connect_timeout",
                "healthcheck_timeout", "healthcheck_delay", "shutdown_timeout", "ban_time",
                "renew_interval"},
    "pools.docgrid": {"pool_mode", "default_role", "query_parser_enabled",
                      "query_parser_read_write_splitting"},
    "pools.docgrid.users.0": {"pool_size", "statement_timeout"},
    "pools.docgrid.shards.0": {"use_patroni", "patroni_port"},
}
PATRONI_FIELDS = {"ttl", "loop_wait", "retry_timeout", "primary_start_timeout",
                  "primary_stop_timeout", "maximum_lag_on_failover", "check_timeline",
                  "synchronous_mode", "synchronous_mode_strict", "failsafe_mode"}
SERVICE_FIELDS = {"Restart", "RestartSec", "KillSignal", "TimeoutStopSec"}
ADMIN_CONFIG_FIELDS = {"prepared_statements_cache_size", "connect_timeout",
                       "shutdown_timeout", "pools.docgrid.pool_mode",
                       "pools.docgrid.default_role", "pools.docgrid.query_parser_enabled",
                       "pools.docgrid.query_parser_read_write_splitting",
                       "pools.docgrid.primary_reads_enabled",
                       "pools.docgrid.load_balancing_mode",
                       "pools.docgrid.prepared_statements_cache_size"}


class ContractError(ValueError):
    """Reject ambiguous or potentially identifying evidence before publication."""


def scalar(raw: str):
    """Parse only the small TOML/YAML scalar subset used by contract settings."""
    value = raw.split("#", 1)[0].strip()
    if value in ("true", "false"):
        return value == "true"
    if re.fullmatch(r"-?\d+", value):
        return int(value)
    if value.startswith(('"', "'")):
        try:
            parsed = ast.literal_eval(value)
        except (SyntaxError, ValueError) as error:
            raise ContractError("설정 문자열을 해석할 수 없습니다") from error
        if isinstance(parsed, str):
            return parsed
    raise ContractError("허용된 단일 설정값 형식이 아닙니다")


def safe_value(value):
    """Keep internal addresses, credentials, and paths out of every generated result."""
    if isinstance(value, str) and PRIVATE_VALUE.search(value):
        raise ContractError("공개할 수 없는 설정값이 포함되었습니다")
    return value


def proxy_settings(path: Path):
    """Read only named scalar keys; never copy a credential-bearing TOML section."""
    settings = {section: {} for section in PROXY_FIELDS}
    section = None
    for line in path.read_text(encoding="utf-8").splitlines():
        heading = re.fullmatch(r"\s*\[([A-Za-z0-9_.]+)\]\s*(?:#.*)?", line)
        if heading:
            section = heading.group(1)
            continue
        if section not in PROXY_FIELDS:
            continue
        assignment = re.match(r"\s*([A-Za-z_][A-Za-z_0-9]*)\s*=\s*(.*)", line)
        if not assignment or assignment.group(1) not in PROXY_FIELDS[section]:
            continue
        key = assignment.group(1)
        if key in settings[section]:
            raise ContractError(f"OpenProxy 설정 중복: {section}.{key}")
        settings[section][key] = safe_value(scalar(assignment.group(2)))
    if not settings["pools.docgrid"] or not settings["pools.docgrid.shards.0"]:
        raise ContractError("DocGrid OpenProxy 풀을 찾지 못했습니다")
    return settings


def patroni_settings(text: str):
    """Extract top-level dynamic keys from patronictl without serializing its full output."""
    settings = {}
    for line in text.splitlines():
        match = re.fullmatch(r"([a-z_]+):\s*(.*?)\s*", line)
        if not match or match.group(1) not in PATRONI_FIELDS:
            continue
        key = match.group(1)
        if key in settings:
            raise ContractError(f"Patroni 동적 설정 중복: {key}")
        settings[key] = safe_value(scalar(match.group(2)))
    if not settings:
        raise ContractError("Patroni 동적 설정을 읽지 못했습니다")
    return {key: settings.get(key) for key in sorted(PATRONI_FIELDS)}


def service_template(path: Path):
    """Label packaged systemd values as a template, not as the active supervisor."""
    if not path.exists():
        return None
    values = {}
    section = None
    for line in path.read_text(encoding="utf-8").splitlines():
        heading = re.fullmatch(r"\s*\[([^]]+)\]\s*", line)
        if heading:
            section = heading.group(1)
        elif section == "Service":
            match = re.fullmatch(r"\s*([A-Za-z]+)\s*=\s*([^#\s]+)\s*", line)
            if match and match.group(1) in SERVICE_FIELDS:
                values[match.group(1)] = safe_value(match.group(2))
    return values


def version(binary: Path, *options):
    """Report only a short version line; suppress arbitrary command output on failure."""
    if not binary.is_file():
        return None
    try:
        result = subprocess.run([str(binary), *options], capture_output=True, text=True,
                                timeout=5, check=True)
    except (OSError, subprocess.SubprocessError):
        return None
    first = result.stdout.splitlines()[0].strip() if result.stdout.splitlines() else ""
    return first if VERSION.fullmatch(first) else None


def opensql_version(binary: Path):
    """Extract the product release from a multiline, banner-style version response."""
    result = subprocess.run([str(binary), "--version"], capture_output=True, text=True,
                            timeout=5, check=True)
    match = re.search(r"^OpenSQL version (v\d+(?:\.\d+)+)$", result.stdout, re.MULTILINE)
    if not match:
        raise ContractError("OpenSQL 제품 버전을 판별할 수 없습니다")
    return match.group(1)


def os_release(path: Path):
    """Expose only distro identity and release, excluding host-specific metadata."""
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"(ID|VERSION_ID)=(.*)", line)
        if match:
            values[match.group(1).lower()] = safe_value(scalar(match.group(2)))
    return values


def time_zone():
    """Record the OS clock label and offset without publishing host metadata."""
    result = subprocess.run(["date", "+%Z%z"], capture_output=True, text=True,
                            timeout=5, check=True)
    value = result.stdout.strip()
    if not re.fullmatch(r"[A-Za-z_+/:-]{2,40}[+-]\d{4}", value):
        raise ContractError("OS 시간대 표기를 판별할 수 없습니다")
    return value


def etcd_timing_inputs(root: Path, proc_root: Path = Path("/proc")):
    """Resolve installed defaults against the one running etcd process's safe timing inputs."""
    processes = []
    for entry in proc_root.iterdir():
        if not entry.name.isdecimal():
            continue
        try:
            if (entry / "comm").read_text(encoding="utf-8").strip() == "etcd":
                processes.append(entry)
        except (OSError, UnicodeError):
            continue
    if len(processes) != 1:
        raise ContractError("실행 중인 etcd 프로세스 하나를 확인하지 못했습니다")
    command = processes[0].joinpath("cmdline").read_bytes().decode("utf-8").split("\0")
    environment = processes[0].joinpath("environ").read_bytes().decode("utf-8").split("\0")
    if any(argument.startswith("--config-file") for argument in command):
        raise ContractError("별도 etcd 설정 파일의 우선순위를 확인해야 합니다")
    help_result = subprocess.run([str(root / "bin/etcd"), "--help"],
                                 capture_output=True, text=True, timeout=5, check=True)
    help_text = help_result.stdout + help_result.stderr
    timing = {}
    for flag, variable, output in (("heartbeat-interval", "ETCD_HEARTBEAT_INTERVAL",
                                    "heartbeat_interval_ms"),
                                   ("election-timeout", "ETCD_ELECTION_TIMEOUT",
                                    "election_timeout_ms")):
        default = re.search(r"--" + flag + r"\s+'(\d+)'", help_text)
        if not default:
            raise ContractError(f"설치된 etcd의 기본값을 확인하지 못했습니다: {flag}")
        explicit = [value.split("=", 1)[1] for value in environment
                    if value.startswith(variable + "=")]
        for index, argument in enumerate(command):
            if argument.startswith("--" + flag + "="):
                explicit.append(argument.split("=", 1)[1])
            elif argument == "--" + flag and index + 1 < len(command):
                explicit.append(command[index + 1])
        if len(explicit) > 1 or (explicit and not explicit[0].isdecimal()):
            raise ContractError(f"실행 중인 etcd 시간 설정을 판별할 수 없습니다: {flag}")
        timing[output] = {"value": int(explicit[0] if explicit else default.group(1)),
                          "source": "process override" if explicit else "installed binary default"}
    return timing


def etcd_settings(root: Path):
    """Keep only HA timing overrides and normalized live member names."""
    environment = root / "etc/etcd/etcd.env"
    configured = {}
    for line in environment.read_text(encoding="utf-8").splitlines():
        if "=" not in line or line.lstrip().startswith("#"):
            continue
        key, raw = line.split("=", 1)
        if key not in {"ETCD_INITIAL_CLUSTER", "ETCD_INITIAL_CLUSTER_STATE",
                       "ETCD_HEARTBEAT_INTERVAL", "ETCD_ELECTION_TIMEOUT"}:
            continue
        if key in configured:
            raise ContractError(f"etcd 설정 중복: {key}")
        configured[key] = raw.strip().strip('"\'')
    names = []
    for member in configured.get("ETCD_INITIAL_CLUSTER", "").split(","):
        name = member.split("=", 1)[0]
        if not re.fullmatch(r"etcd[123]", name):
            raise ContractError("etcd 초기 멤버 이름을 안전하게 정규화할 수 없습니다")
        names.append(name.replace("etcd", "node"))
    if sorted(names) != ["node1", "node2", "node3"]:
        raise ContractError("etcd 초기 멤버 세 개를 확인하지 못했습니다")
    result = subprocess.run([str(root / "bin/etcdctl"),
                             "--endpoints=http://127.0.0.1:2379", "member", "list",
                             "--write-out=json"], capture_output=True, text=True,
                            timeout=10, check=True)
    live = json.loads(result.stdout).get("members", [])
    live_names = []
    for member in live:
        name = member.get("name", "")
        if not re.fullmatch(r"etcd[123]", name) or member.get("isLearner", False):
            raise ContractError("etcd 실행 멤버 구성을 판별할 수 없습니다")
        live_names.append(name.replace("etcd", "node"))
    if sorted(live_names) != sorted(names):
        raise ContractError("etcd 초기 설정과 실행 멤버가 일치하지 않습니다")
    timing = {}
    for key, output in (("ETCD_HEARTBEAT_INTERVAL", "heartbeat_interval_ms"),
                        ("ETCD_ELECTION_TIMEOUT", "election_timeout_ms")):
        raw = configured.get(key)
        if raw is not None and (not raw.isdecimal() or int(raw) <= 0):
            raise ContractError(f"etcd 시간 설정을 판별할 수 없습니다: {key}")
        timing[output] = int(raw) if raw is not None else None
    return {"initial_cluster_state": safe_value(configured.get("ETCD_INITIAL_CLUSTER_STATE")),
            "configured_member_names": sorted(names), "live_member_names": sorted(live_names),
            "quorum": len(live_names) // 2 + 1, "explicit_timing": timing,
            "running_timing": etcd_timing_inputs(root)}


def patroni_members(text: str):
    """Normalize member names and roles without exposing REST addresses."""
    members = json.loads(text)
    if not isinstance(members, list) or len(members) != 3:
        raise ContractError("Patroni 멤버 세 개를 확인하지 못했습니다")
    normalized = []
    for member in members:
        name = member.get("Member")
        match = re.fullmatch(r"(?:docgrid-)?node([123])|postgresql([123])", name or "")
        if not match:
            raise ContractError("Patroni 멤버 이름을 안전한 별칭으로 바꿀 수 없습니다")
        role = member.get("Role")
        state = member.get("State")
        if role not in {"Leader", "Replica", "Sync Standby", "Standby Leader"} or not isinstance(state, str):
            raise ContractError("Patroni 역할 또는 상태를 판별할 수 없습니다")
        normalized.append({"node": "node" + (match.group(1) or match.group(2)),
                           "role": role, "state": safe_value(state)})
    if {member["node"] for member in normalized} != {"node1", "node2", "node3"}:
        raise ContractError("Patroni 멤버 이름이 중복되거나 누락되었습니다")
    return sorted(normalized, key=lambda member: member["node"])


def collect(args):
    """Build one read-only node snapshot from installed configuration and binaries."""
    if not NODE.fullmatch(args.node):
        raise ContractError("노드 별칭은 node1~node3이어야 합니다")
    root = args.install_root
    document = {
        "schema_version": 1,
        "node": args.node,
        "os": {**os_release(args.os_release), "architecture": platform.machine()},
        "container_time_zone": time_zone(),
        "versions": {
            "opensql": opensql_version(root / "bin/opensql"),
            "openproxy": version(root / "bin/openproxy", "--version"),
            "openproxy_revision": version(root / "bin/openproxy", "--revision"),
            "patroni": version(root / "bin/patroni", "--version"),
            "etcd": version(root / "bin/etcd", "--version"),
            "psql_client": version(root / "bin/psql", "--version"),
            "postgres_server_binary": version(root / "bin/postgres", "--version"),
        },
        "etcd": etcd_settings(root),
        "patroni_dynamic": patroni_settings(subprocess.run(
            [str(root / "bin/patronictl"), "-c", str(root / "etc/patroni/patroni.yml"),
            "show-config"], capture_output=True, text=True, timeout=10,
            check=True).stdout),
        "patroni_members": patroni_members(subprocess.run(
            [str(root / "bin/patronictl"), "-c", str(root / "etc/patroni/patroni.yml"),
             "list", "--format", "json"], capture_output=True, text=True, timeout=10,
            check=True).stdout),
    }
    proxy = root / "etc/openproxy/openproxy.toml"
    if proxy.exists():
        document["openproxy"] = proxy_settings(proxy)
        document["openproxy_service_template"] = service_template(
            root / "etc/openproxy/openproxy.service")
    return document


def runtime(args):
    """Report the active container and host unit, distinct from a packaged unit template."""
    if not NODE.fullmatch(args.node):
        raise ContractError("노드 별칭은 node1~node3이어야 합니다")
    container = f"docgrid-{args.node}"
    inspect = subprocess.run(["sudo", "-n", "docker", "inspect", "--format",
                              "{{.HostConfig.RestartPolicy.Name}}", container],
                             capture_output=True, text=True, timeout=5, check=True)
    unit = subprocess.run(["systemctl", "show", f"docgrid-opensql@{container}.service",
                           "-p", "ActiveState", "-p", "Restart", "-p", "RestartUSec"],
                          capture_output=True, text=True, timeout=5, check=True)
    fields = dict(line.split("=", 1) for line in unit.stdout.splitlines() if "=" in line)
    processes = subprocess.run(["sudo", "-n", "docker", "exec", container,
                                "ps", "-eo", "ppid=,stat=,comm="],
                               capture_output=True, text=True, timeout=5, check=True)
    openproxy_parents = []
    for line in processes.stdout.splitlines():
        columns = line.split()
        if len(columns) >= 3 and columns[2] == "openproxy" and not columns[1].startswith("Z"):
            openproxy_parents.append(columns[0])
    if inspect.stdout.strip() not in {"always", "unless-stopped", "no", "on-failure"}:
        raise ContractError("컨테이너 재시작 정책을 판별할 수 없습니다")
    return {"node": args.node, "container_restart_policy": inspect.stdout.strip(),
            "host_time_zone": time_zone(),
            "host_bootstrap_unit": {key: safe_value(fields.get(key)) for key in
                                    ("ActiveState", "Restart", "RestartUSec")},
            "openproxy_live_process_count": len(openproxy_parents),
            "openproxy_parent_is_container_pid1": all(parent == "1" for parent in openproxy_parents)
            if openproxy_parents else None}


def admin_credentials(path: Path):
    """Read administrator credentials only in container memory, never in collector output."""
    fields = {}
    section = None
    for line in path.read_text(encoding="utf-8").splitlines():
        heading = re.fullmatch(r"\s*\[([A-Za-z0-9_.]+)\]\s*(?:#.*)?", line)
        if heading:
            section = heading.group(1)
            continue
        if section != "general":
            continue
        match = re.match(r"\s*(port|admin_port|admin_username|admin_password)\s*=\s*(.*)", line)
        if match:
            fields[match.group(1)] = scalar(match.group(2))
    if not isinstance(fields.get("port"), int) or not all(
            isinstance(fields.get(key), str) for key in ("admin_username", "admin_password")):
        raise ContractError("OpenProxy 관리자 접속 정보를 읽지 못했습니다")
    return fields


def admin_rows(root: Path, credentials: dict, command: str):
    """Run a read-only SHOW command; keep psql stdout/stderr and password in memory."""
    environment = os.environ.copy()
    environment["PGPASSWORD"] = credentials["admin_password"]
    try:
        result = subprocess.run(
            [str(root / "bin/psql"), "-X", "-w", "--csv", "-P", "footer=off",
             "-h", "127.0.0.1", "-p", str(credentials["port"]),
             "-d", "openproxy", "-U", credentials["admin_username"], "-c", command],
            capture_output=True, text=True, timeout=10, check=True, env=environment)
    except subprocess.CalledProcessError as error:
        reason = ("authentication" if "authentication failed" in error.stderr.lower() else
                  "connection" if "connection refused" in error.stderr.lower() else
                  "psql-command")
        raise ContractError(f"{command} 조회 실패 ({reason})") from error
    return list(csv.DictReader(io.StringIO(result.stdout)))


def admin_snapshot(args):
    """Expose only effective contract settings and role-level counters from OpenProxy."""
    if args.node not in {"node2", "node3"}:
        raise ContractError("관리 콘솔은 node2 또는 node3에서만 수집합니다")
    root = args.install_root
    credentials = admin_credentials(root / "etc/openproxy/openproxy.toml")
    config = {}
    for row in admin_rows(root, credentials, "SHOW CONFIG"):
        key = row.get("key", "").strip()
        if key in ADMIN_CONFIG_FIELDS:
            config[key] = safe_value(scalar(row.get("value", "").strip()))
    if not config:
        raise ContractError("OpenProxy 실제 설정을 확인하지 못했습니다")
    stats = []
    for row in admin_rows(root, credentials, "SHOW STATS"):
        if row.get("database") != "docgrid":
            continue
        instance = row.get("instance", "")
        match = re.fullmatch(r"docgrid_shard_\d+_(primary|replica_\d+)", instance)
        if not match:
            raise ContractError("OpenProxy 통계 역할을 안전하게 정규화할 수 없습니다")
        stats.append({"role": match.group(1), "queries": int(row["total_query_count"]),
                      "transactions": int(row["total_xact_count"]),
                      "errors": int(row["total_errors"])})
    servers = []
    for row in admin_rows(root, credentials, "SHOW SERVERS"):
        if row.get("database_name") != "docgrid":
            continue
        match = re.fullmatch(r"docgrid_shard_\d+_(primary|replica_\d+)",
                             row.get("address_id", ""))
        if not match:
            raise ContractError("OpenProxy 서버 역할을 안전하게 정규화할 수 없습니다")
        servers.append({"role": match.group(1),
                        "prepare_cache_hit": int(row["prepare_cache_hit"]),
                        "prepare_cache_miss": int(row["prepare_cache_miss"]),
                        "prepare_cache_eviction": int(row["prepare_cache_eviction"]),
                        "prepare_cache_size": int(row["prepare_cache_size"])})
    return {"node": args.node, "proxy": "proxy-a" if args.node == "node2" else "proxy-b",
            "effective_config": config,
            "stats": sorted(stats, key=lambda row: row["role"]),
            "servers": sorted(servers, key=lambda row: row["role"])}


def merge_node(collect_path: Path, runtime_path: Path):
    """Join independently collected container and VM observations without hand editing."""
    node = json.loads(collect_path.read_text(encoding="utf-8"))
    runtime_data = json.loads(runtime_path.read_text(encoding="utf-8"))
    if node.get("node") != runtime_data.get("node") or not NODE.fullmatch(node.get("node", "")):
        raise ContractError("컨테이너와 VM 수집 결과의 노드가 다릅니다")
    if "runtime" in node or PRIVATE_VALUE.search(json.dumps({"node": node, "runtime": runtime_data})):
        raise ContractError("중복 또는 공개 불가능한 수집 결과입니다")
    node["runtime"] = {key: value for key, value in runtime_data.items() if key != "node"}
    return node


def assemble(paths, admin_paths=None):
    """Compare A/B contracts and hash all supplied, sanitized node/admin observations."""
    nodes = [json.loads(path.read_text(encoding="utf-8")) for path in paths]
    if {node.get("node") for node in nodes} != {"node1", "node2", "node3"}:
        raise ContractError("node1~node3 결과가 각각 하나씩 필요합니다")
    for node in nodes:
        if node.get("schema_version") != 1 or PRIVATE_VALUE.search(json.dumps(node)):
            raise ContractError("스냅샷 형식 또는 공개 가능성을 확인할 수 없습니다")
    by_name = {node["node"]: node for node in nodes}
    expected_os = {"id": "rocky", "version_id": "9.7", "architecture": "x86_64"}
    if any(node.get("os") != expected_os for node in nodes):
        raise ContractError("세 노드의 Rocky Linux 9.7 x86_64 구성이 일치하지 않습니다")
    if "openproxy" in by_name["node1"]:
        raise ContractError("node1에 예상하지 않은 OpenProxy 설정이 있습니다")
    if by_name["node2"].get("openproxy") != by_name["node3"].get("openproxy"):
        raise ContractError("두 OpenProxy의 계약 관련 설정이 다릅니다")
    if by_name["node2"].get("versions", {}).get("openproxy") != by_name["node3"].get("versions", {}).get("openproxy"):
        raise ContractError("두 OpenProxy의 설치 버전이 다릅니다")
    product_versions = [node.get("versions", {}).get("opensql") for node in nodes]
    if any(product_versions) and (None in product_versions or len(set(product_versions)) != 1):
        raise ContractError("세 노드의 OpenSQL 제품 버전이 다릅니다")
    if len({json.dumps(node.get("patroni_dynamic"), sort_keys=True) for node in nodes}) != 1:
        raise ContractError("Patroni 동적 설정의 노드별 조회 결과가 다릅니다")
    snapshot = {"schema_version": 1, "nodes": [by_name[name] for name in sorted(by_name)]}
    if admin_paths:
        admins = [json.loads(path.read_text(encoding="utf-8")) for path in admin_paths]
        if {admin.get("proxy") for admin in admins} != {"proxy-a", "proxy-b"} or any(
                PRIVATE_VALUE.search(json.dumps(admin)) for admin in admins):
            raise ContractError("관리 결과 별칭 또는 공개 가능성을 확인할 수 없습니다")
        by_proxy = {admin["proxy"]: admin for admin in admins}
        if by_proxy["proxy-a"].get("node") != "node2" or by_proxy["proxy-b"].get("node") != "node3":
            raise ContractError("관리 결과와 프록시 노드가 일치하지 않습니다")
        if by_proxy["proxy-a"].get("effective_config") != by_proxy["proxy-b"].get("effective_config"):
            raise ContractError("두 OpenProxy의 관리 콘솔 설정이 다릅니다")
        snapshot["admins"] = [by_proxy[name] for name in sorted(by_proxy)]
    canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True,
                           separators=(",", ":")).encode("utf-8")
    return {"snapshot": snapshot, "evidence_sha256": hashlib.sha256(canonical).hexdigest()}


def junit_summary(path: Path):
    """Preserve test cases and allowlisted observations without machine names or secrets."""
    suite = ET.parse(path).getroot()
    if suite.tag != "testsuite":
        raise ContractError("JUnit 시험 결과 형식을 확인할 수 없습니다")
    timestamp = suite.get("timestamp")
    if timestamp is not None and not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z", timestamp):
        raise ContractError("JUnit 실행 시각을 판별할 수 없습니다")
    cases = []
    for case in suite.findall("testcase"):
        cases.append({"name": safe_value(case.get("name", "")),
                      "status": "failed" if case.find("failure") is not None else
                                "error" if case.find("error") is not None else
                                "skipped" if case.find("skipped") is not None else "passed"})
    observations = []
    for line in (suite.findtext("system-out") or "").splitlines():
        if line.startswith("CONTRACT_"):
            if PRIVATE_VALUE.search(line) or not re.fullmatch(
                    r"CONTRACT_[A-Z_]+ [A-Za-z0-9_=./:+ -]{1,300}", line):
                raise ContractError("공개할 수 없는 시험 관측값이 포함되었습니다")
            observations.append(line)
    return {"tests": int(suite.get("tests", "0")),
            "executed_at_utc": timestamp,
            "duration_seconds": float(suite.get("time", "0")),
            "failures": int(suite.get("failures", "0")),
            "errors": int(suite.get("errors", "0")),
            "skipped": int(suite.get("skipped", "0")),
            "cases": cases, "observations": observations}


def main(argv=None):
    """Keep collection and local assembly explicit so raw configs are never transferred."""
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    one = actions.add_parser("collect")
    one.add_argument("--node", required=True)
    one.add_argument("--install-root", type=Path, default=Path("/var/lib/docgrid/opensql"))
    one.add_argument("--os-release", type=Path, default=Path("/etc/os-release"))
    host = actions.add_parser("runtime")
    host.add_argument("--node", required=True)
    admin = actions.add_parser("admin")
    admin.add_argument("--node", required=True)
    admin.add_argument("--install-root", type=Path, default=Path("/var/lib/docgrid/opensql"))
    merge = actions.add_parser("merge")
    merge.add_argument("collect_json", type=Path)
    merge.add_argument("runtime_json", type=Path)
    many = actions.add_parser("assemble")
    many.add_argument("node_json", nargs=3, type=Path)
    many.add_argument("--admin", nargs=2, type=Path)
    junit = actions.add_parser("junit")
    junit.add_argument("xml", type=Path)
    args = parser.parse_args(argv)
    try:
        data = (collect(args) if args.action == "collect" else
                runtime(args) if args.action == "runtime" else
                admin_snapshot(args) if args.action == "admin" else
                merge_node(args.collect_json, args.runtime_json) if args.action == "merge" else
                junit_summary(args.xml) if args.action == "junit" else
                assemble(args.node_json, args.admin))
        print(json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2))
        return 0
    except ContractError as error:
        print(f"HA 계약 수집 실패: {error}", file=sys.stderr)
        return 1
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError, ET.ParseError) as error:
        print(f"HA 계약 수집 실패: {type(error).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
