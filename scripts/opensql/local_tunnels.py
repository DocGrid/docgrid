#!/usr/bin/env python3
"""Manage per-user macOS SSH tunnels for the local OpenSQL HA profile."""

import argparse
import json
import os
import plistlib
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONFIG = Path.home() / ".config" / "docgrid" / "opensql-tunnels.json"
AGENTS = Path.home() / "Library" / "LaunchAgents"
NODES = ("node1", "node2", "node3")
LABEL_PREFIX = "com.docgrid.opensql-tunnel"
CONFIG_KEYS = ("project", "zone", *NODES)


def die(message):
    raise SystemExit(message)


def validate_config(values):
    if not isinstance(values, dict) or set(values) != set(CONFIG_KEYS):
        die("설정에는 project, zone, node1, node2, node3만 있어야 합니다.")
    for key in CONFIG_KEYS:
        value = values[key]
        if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", value):
            die(f"{key} 형식이 잘못되었습니다.")
    return values


def read_config():
    if not CONFIG.is_file():
        die("개인 설정이 없습니다. 먼저 configure를 실행하세요.")
    info = CONFIG.stat()
    if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) & 0o077:
        die("개인 설정 파일은 현재 사용자 소유이며 권한이 0600이어야 합니다.")
    try:
        return validate_config(json.loads(CONFIG.read_text(encoding="utf-8")))
    except (OSError, ValueError) as error:
        die(f"개인 설정 JSON을 읽을 수 없습니다: {type(error).__name__}")


def configure():
    if CONFIG.exists():
        die("개인 설정이 이미 있습니다. 값을 수정한 뒤 install을 다시 실행하세요.")
    labels = {"project": "GCP 프로젝트 ID", "zone": "VM 영역", "node1": "node1 VM 이름",
              "node2": "node2 VM 이름", "node3": "node3 VM 이름"}
    values = validate_config({key: input(f"{labels[key]}: ").strip() for key in CONFIG_KEYS})
    CONFIG.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        json.dump(values, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print("개인 설정을 0600 권한으로 저장했습니다. VM 식별자는 출력하지 않습니다.")


def read_env_urls():
    env_file = ROOT / ".env"
    if not env_file.is_file():
        die("저장소 루트의 .env가 없습니다.")
    wanted = {"OPENSQL_APP_JDBC_URL", "OPENSQL_MIGRATION_JDBC_URL"}
    urls = {}
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in wanted:
            if key in urls:
                die(f".env에서 {key}가 중복됐습니다.")
            urls[key] = value.strip().strip('"').strip("'")
    if set(urls) != wanted:
        die(".env의 OpenSQL 앱·Flyway JDBC URL 두 개가 모두 필요합니다.")
    return urls


def loopback_ports(url, count):
    prefix = "jdbc:postgresql://"
    if not url.startswith(prefix) or "/" not in url[len(prefix):]:
        die("OpenSQL JDBC URL 형식이 잘못됐습니다.")
    authority = url[len(prefix):].split("/", 1)[0]
    hosts = authority.split(",")
    if len(hosts) != count:
        die(f"OpenSQL JDBC URL은 loopback 주소 {count}개가 필요합니다.")
    ports = []
    for host in hosts:
        match = re.fullmatch(r"(?:127\.0\.0\.1|localhost):([0-9]{1,5})", host)
        if not match or not 1 <= int(match.group(1)) <= 65535:
            die("OpenSQL JDBC URL은 유효한 localhost 포트만 사용할 수 있습니다.")
        ports.append(int(match.group(1)))
    if len(set(ports)) != count:
        die("한 JDBC URL 안에 중복된 로컬 포트가 있습니다.")
    return ports


def port_plan():
    urls = read_env_urls()
    app = loopback_ports(urls["OPENSQL_APP_JDBC_URL"], 2)
    migration = loopback_ports(urls["OPENSQL_MIGRATION_JDBC_URL"], 3)
    if len(set(app + migration)) != 5:
        die("앱과 Flyway의 로컬 터널 포트가 겹칩니다.")
    # 1. Flyway는 DB node1/2/3, 앱은 node2/3의 OpenProxy A/B에 순서대로 대응한다.
    return {"node1": [(migration[0], 5432)],
            "node2": [(migration[1], 5432), (app[0], 6432)],
            "node3": [(migration[2], 5432), (app[1], 6432)]}


def label(node):
    return f"{LABEL_PREFIX}.{node}"


def plist_path(node):
    return AGENTS / f"{label(node)}.plist"


def launch_domain():
    return f"gui/{os.getuid()}"


def launchctl(*args):
    return subprocess.run(("launchctl", *args), stdout=subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, check=False).returncode == 0


def loaded(node):
    return launchctl("print", f"{launch_domain()}/{label(node)}")


def require_macos():
    if sys.platform != "darwin" or not shutil.which("launchctl"):
        die("자동 시작은 macOS launchd에서만 지원합니다.")


def make_plist(node, gcloud_bin):
    # 2. launchd는 출력 내용을 파일에 보관하지 않고 SSH 프로세스 종료 시 다시 시작한다.
    search_path = os.pathsep.join(dict.fromkeys((str(Path(gcloud_bin).parent),
                                                  os.environ.get("PATH", ""),
                                                  "/usr/bin", "/bin")))
    return {"Label": label(node),
            "ProgramArguments": [sys.executable, str(Path(__file__).resolve()), "serve", node],
            "WorkingDirectory": str(ROOT),
            "EnvironmentVariables": {"DOCGRID_GCLOUD_BIN": gcloud_bin,
                                     "DOCGRID_TUNNEL_QUIET": "1", "PATH": search_path},
            "RunAtLoad": True, "KeepAlive": True, "ThrottleInterval": 30,
            "StandardOutPath": "/dev/null", "StandardErrorPath": "/dev/null"}


def write_plist(node, gcloud_bin):
    destination = plist_path(node)
    with tempfile.NamedTemporaryFile(dir=AGENTS, prefix=".docgrid-tunnel-", delete=False) as output:
        temporary = Path(output.name)
        os.fchmod(output.fileno(), 0o600)
        plistlib.dump(make_plist(node, gcloud_bin), output)
    os.replace(temporary, destination)


def start():
    require_macos()
    paths = {node: plist_path(node) for node in NODES}
    if any(not path.is_file() for path in paths.values()):
        die("LaunchAgent가 없습니다. 먼저 install을 실행하세요.")
    started = []
    for node in NODES:
        if not loaded(node):
            if not launchctl("bootstrap", launch_domain(), str(paths[node])):
                for previous in started:
                    launchctl("bootout", launch_domain(), str(paths[previous]))
                die(f"{node} LaunchAgent 시작에 실패했습니다. 사용자 로그인 세션을 확인하세요.")
            started.append(node)
    print("터널 LaunchAgent 3개를 등록했습니다. status로 실제 연결을 확인하세요.")


def stop():
    require_macos()
    for node in NODES:
        if loaded(node) and not launchctl("bootout", launch_domain(), str(plist_path(node))):
            die(f"{node} LaunchAgent 중지에 실패했습니다.")
    print("터널 LaunchAgent 3개를 중지했습니다.")


def install():
    require_macos()
    read_config()
    port_plan()
    gcloud_bin = shutil.which("gcloud")
    if not gcloud_bin:
        die("gcloud를 찾을 수 없습니다. Google Cloud CLI를 먼저 설치하세요.")
    # 3. 기존 Agent를 먼저 내리고 검증된 설정으로 교체해 포트 변경을 반영한다.
    stop()
    AGENTS.mkdir(parents=True, exist_ok=True)
    for node in NODES:
        write_plist(node, gcloud_bin)
    start()


def serve(node):
    values = read_config()
    forwards = port_plan()[node]
    gcloud_bin = os.environ.get("DOCGRID_GCLOUD_BIN") or shutil.which("gcloud")
    if not gcloud_bin:
        die("gcloud를 찾을 수 없습니다.")
    command = [gcloud_bin, "compute", "ssh", values[node],
               f"--project={values['project']}", f"--zone={values['zone']}",
               "--tunnel-through-iap", "--", "-N", "-o", "BatchMode=yes",
               # 암호 문구가 있는 키도 무인 실행에서 macOS Keychain으로 해제한다.
               "-o", "UseKeychain=yes",
               "-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=30",
               "-o", "ServerAliveCountMax=3"]
    for local_port, remote_port in forwards:
        command.extend(("-L", f"127.0.0.1:{local_port}:127.0.0.1:{remote_port}"))
    quiet = os.environ.get("DOCGRID_TUNNEL_QUIET") == "1"
    # 4. 비밀값·VM 식별자가 포함될 수 있는 gcloud 진단은 자동 실행 로그에 기록하지 않는다.
    return subprocess.run(command, stdin=subprocess.DEVNULL if quiet else None,
                          stdout=subprocess.DEVNULL if quiet else None,
                          stderr=subprocess.DEVNULL if quiet else None,
                          check=False).returncode


def status():
    require_macos()
    plan = port_plan()
    connected = True
    first_failed_node = None
    for node in NODES:
        agent_loaded = loaded(node)
        connected &= agent_loaded
        node_connected = agent_loaded
        ports = []
        for local_port, _ in plan[node]:
            listening = subprocess.run(("lsof", "-nP", f"-iTCP:{local_port}", "-sTCP:LISTEN"),
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       check=False).returncode == 0
            connected &= listening
            node_connected &= listening
            ports.append(f"{local_port}:{'열림' if listening else '닫힘'}")
        print(f"{node} agent={'등록' if agent_loaded else '미등록'} local={','.join(ports)}")
        if not node_connected and first_failed_node is None:
            first_failed_node = node
    print("로컬 포트 상태는 원격 DB 인증·정상 동작까지 증명하지 않습니다.")
    if not connected:
        print(f"터널이 연결되지 않았습니다. stop 후 serve {first_failed_node}로 SSH 오류를 확인하세요.")
        return 1
    return 0


def uninstall():
    stop()
    for node in NODES:
        plist_path(node).unlink(missing_ok=True)
    print("LaunchAgent를 제거했습니다. 개인 VM 설정은 보존했습니다.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("configure", "install", "start", "status", "stop",
                                           "uninstall", "serve"))
    parser.add_argument("node", nargs="?", choices=NODES)
    args = parser.parse_args()
    if args.action == "serve":
        if not args.node:
            parser.error("serve에는 node1, node2 또는 node3가 필요합니다.")
        return serve(args.node)
    if args.node:
        parser.error("노드는 serve 명령에만 지정합니다.")
    result = {"configure": configure, "install": install, "start": start, "status": status,
              "stop": stop, "uninstall": uninstall}[args.action]()
    return 0 if result is None else result


if __name__ == "__main__":
    sys.exit(main())
