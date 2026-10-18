#!/usr/bin/env python3
"""로컬 시연의 시작·프런트엔드 경유 검증·소유 자원 종료를 기존 도구로 연결한다."""
from __future__ import annotations

import argparse
import contextlib
import fcntl
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "demo"))
from local_config import ConfigError, LocalConfig
import seed_demo_corpus as seed

SERVICES = ("postgres", "valkey", "embedding-server")


class DemoError(Exception):
    """비밀값이나 임의 하위 명령 출력을 포함하지 않는 실행 관리 오류다."""


def now():
    return datetime.now(timezone(timedelta(hours=9))).isoformat(timespec="seconds")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def write_json(path, value):
    """운영 상태는 동일 디렉터리의 임시 파일을 원자 교체하고 실행 로그는 별도로 유지한다."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as target:
        json.dump(value, target, ensure_ascii=False, indent=2)
    os.replace(temporary, path)


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise DemoError("실행 상태를 읽을 수 없습니다. 상태 파일을 임의로 덮어쓰지 않습니다.") from None


def identity(pid):
    """자신이 기록한 PID의 그룹·시작 시각·명령 fingerprint를 재확인한다."""
    if type(pid) is not int or pid <= 1:
        return None
    result = subprocess.run(["ps", "-ww", "-p", str(pid), "-o", "pid=,pgid=,stat=,lstart=,args="],
                            capture_output=True, text=True, env={**os.environ, "LC_ALL": "C"})
    parts = result.stdout.split()
    if result.returncode or len(parts) < 9 or "Z" in parts[2]:
        return None
    return {"pid": int(parts[0]), "pgid": int(parts[1]), "started": " ".join(parts[3:8]),
            "commandHash": digest(" ".join(parts[8:]))}


def group_members(pgid):
    result = subprocess.run(["ps", "-eo", "pid=,pgid=,stat="], capture_output=True, text=True)
    if result.returncode:
        raise DemoError("프로세스 그룹 상태를 확인할 수 없습니다.")
    members = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[1] == str(pgid) and "Z" not in parts[2]:
            members.append(int(parts[0]))
    return members


def port_open(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.3):
            return True
    except OSError:
        return False


def listener_owned(port, record):
    """HTTP 성공 외에 실제 수신 프로세스가 소유 그룹에 속하는지도 확인한다."""
    result = subprocess.run(["lsof", "-nP", "-a", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
                            capture_output=True, text=True)
    pids = set(result.stdout.split())
    if result.returncode or not pids:
        return False
    return all(p.isdigit() and (item := identity(int(p))) and item["pgid"] == record["pgid"] for p in pids)


def http_bytes(url):
    try:
        with urllib.request.build_opener(seed.NoRedirect).open(url, timeout=2) as response:
            raw = response.read(1048577)
            if response.status != 200 or len(raw) > 1048576:
                return None
            return raw
    except (OSError, ValueError, urllib.error.URLError):
        return None


def http_json(url):
    try:
        raw = http_bytes(url)
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def source_fingerprint(root):
    """생성 산출물·개인 환경 파일을 제외하고 실제 실행 소스의 변경을 감지한다."""
    result = subprocess.run(["git", "ls-files", "-z"], cwd=root, capture_output=True, text=True)
    if result.returncode:
        raise DemoError("Git 작업공간에서 실행하세요.")
    values = {}
    for name in result.stdout.split("\0"):
        if name and (name.startswith(("backend/", "frontend/", "scripts/demo")) or
                     name in {"docker-compose.yml", "scripts/local-services.sh"}):
            path = root / name
            if path.is_file():
                values[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return digest(values)


def config_fingerprint(config):
    keys = set(seed_key for seed_key in config.values if seed_key.startswith((
        "DB_", "REDIS_", "STORAGE_", "EMBEDDING_", "OLLAMA_")))
    keys.update(["SPRING_PROFILES_ACTIVE", "SERVER_PORT", "MANAGEMENT_PORT", "BACKEND_API_URL",
                 "NEXT_PUBLIC_BACKEND_WS_URL", "JWT_SECRET", "DOCKER_HOST"])
    return digest({key: config.values.get(key) for key in keys})


def endpoint_reference(endpoint):
    """Unix 소켓의 사용자 홈은 원문 사용자명 대신 상대 참조로 보관한다."""
    path = Path(endpoint.removeprefix("unix://"))
    try:
        return {"base": "home", "path": str(path.relative_to(Path.home()))}
    except ValueError:
        return {"base": "absolute", "path": str(path)}


class DemoRuntime:
    """한 로컬 작업공간의 세션·소유 자원·취소 정리만 관리하며 업무 API 로직은 위임한다."""

    def __init__(self, root=ROOT):
        self.root = Path(root).resolve()
        self.directory = self.root / ".demo" / "launcher"
        self.active = self.directory / "active.json"
        self.state = None
        self.config = None
        self.handles = {}

    @contextlib.contextmanager
    def lock(self):
        if (self.root / ".demo").is_symlink() or self.directory.is_symlink():
            raise DemoError("실행 상태 경로의 심볼릭 링크는 지원하지 않습니다.")
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        fd = os.open(self.directory / "command.lock", os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise DemoError("다른 시작·검증·종료 명령이 실행 중입니다.") from None
            yield
        finally:
            os.close(fd)

    def session_path(self, session_id):
        if not isinstance(session_id, str) or not re.fullmatch(r"[a-f0-9]{32}", session_id):
            raise DemoError("실행 세션 식별자가 잘못됐습니다.")
        path = self.directory / "sessions" / session_id
        if ((self.directory / "sessions").is_symlink() or path.is_symlink() or
            self.directory.resolve() not in path.resolve().parents):
            raise DemoError("세션 상태 경로의 외부 연결은 지원하지 않습니다.")
        return path

    def save(self):
        write_json(self.session_path(self.state["id"]) / "state.json", self.state)
        write_json(self.active, {"id": self.state["id"]})

    def component_path(self, component, suffix):
        prefix = component
        if component == "verify":
            operation = self.state.get("verification", {}).get("id")
            if not isinstance(operation, str) or not re.fullmatch(r"[a-f0-9]{32}", operation):
                raise DemoError("검증 실행 식별자가 잘못됐습니다.")
            prefix += "-" + operation
        return self.session_path(self.state["id"]) / (prefix + suffix)

    def load(self):
        if (self.root / ".demo").is_symlink() or self.directory.is_symlink() or self.active.is_symlink():
            raise DemoError("실행 상태 경로의 외부 연결은 지원하지 않습니다.")
        if not self.active.exists():
            return None
        active = read_json(self.active)
        self.state = read_json(self.session_path(active["id"]) / "state.json")
        # 자식이 먼저 기록하므로 부모가 갑자기 종료돼도 알려진 세션의 소유 PID를 회수할 수 있다.
        for name in ["install", "services", "backend", "frontend", "verify"]:
            if name == "verify" and not self.state.get("verification"):
                continue
            path = self.component_path(name, ".pid.json")
            if path.exists():
                self.state.setdefault("processes", {})[name] = read_json(path)
        return self.state

    def event(self, message):
        safe = seed.safe_text(message)
        print(safe, flush=True)
        if self.state:
            path = self.session_path(self.state["id"]) / "events.jsonl"
            with path.open("a", encoding="utf-8") as target:
                target.write(json.dumps({"timestamp": now(), "message": safe}, ensure_ascii=False) + "\n")

    def prepare_files(self):
        for name, template in [(".env", ".env.example"), ("frontend/.env.local", "frontend/.env.example")]:
            path = self.root / name
            if path.exists() or path.is_symlink():
                continue
            text = (self.root / template).read_text(encoding="utf-8")
            if name == ".env":
                text += "\nJWT_SECRET=" + secrets.token_urlsafe(48) + "\n"
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as target:
                target.write(text)

    def check_tools(self):
        for name in ["git", "docker", "java", "node", "npm", "curl", "lsof", "ps", "bash"]:
            if not shutil.which(name):
                raise DemoError(f"필수 실행 도구가 없습니다: {name}")
        node = subprocess.run(["node", "--version"], capture_output=True, text=True).stdout.strip()
        match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", node)
        if not match or tuple(map(int, match.groups())) < (22, 13, 0):
            raise DemoError("Node.js 22.13.0 이상이 필요합니다.")
        java = subprocess.run(["java", "-version"], capture_output=True, text=True)
        if not re.search(r'version "17[.\"]', java.stderr + java.stdout):
            raise DemoError("JAVA_HOME·PATH에 Java 17을 준비하세요.")

    def snapshot(self):
        output = {}
        for service in SERVICES:
            ps = self.config.command(self.config.compose("ps", "--all", "--quiet", service))
            if ps.returncode:
                raise DemoError("Compose 자원 상태를 확인할 수 없습니다.")
            if not ps.stdout.strip():
                output[service] = None
            else:
                data = self.config.container(service)
                output[service] = {"id": data["Id"], "running": data["State"]["Running"]}
        return output

    def reconcile_containers(self):
        current = self.snapshot()
        for service, item in current.items():
            before = self.state["before"].get(service)
            if before and item and before["id"] != item["id"]:
                raise DemoError("기동 중 컨테이너 식별자가 변경됐습니다. 자동 교체하지 않습니다.")
            if item:
                self.state["containers"][service] = {**item, "owned": not before or not before["running"]}
        self.save()

    def spawn(self, component):
        env = {**self.config.values}
        if component != "verify":
            env.pop("DOCGRID_DEMO_PASSWORD", None)
        proc = subprocess.Popen([sys.executable, str(self.root / "scripts/demo.py"), "_serve",
                                 self.state["id"], component], cwd=self.root, env=env,
                                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL, start_new_session=True)
        self.handles[component] = proc
        provisional = identity(proc.pid)
        if not provisional or provisional["pgid"] != proc.pid:
            proc.terminate()
            raise DemoError("전용 프로세스 식별정보를 기록할 수 없습니다.")
        self.state["processes"][component] = provisional
        self.save()
        path = self.component_path(component, ".pid.json")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and not path.exists() and proc.poll() is None:
            time.sleep(0.05)
        if not path.exists():
            raise DemoError(f"전용 실행 프로세스가 준비되지 않았습니다: {component}")
        record = read_json(path)
        if record.get("pid") != proc.pid or record.get("pgid") != proc.pid:
            raise DemoError("전용 프로세스 그룹을 확인할 수 없습니다.")
        self.state["processes"][component] = record
        self.save()
        return record

    def wait(self, predicate, timeout, label, component=None):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            if component:
                outcome = self.component_path(component, ".exit.json")
                record = self.state.get("processes", {}).get(component)
                if outcome.exists() or (record and identity(record.get("pid")) != record):
                    raise DemoError(f"준비 전에 실행이 종료됐습니다: {label}")
            time.sleep(0.5)
        raise DemoError(f"준비 제한 시간을 초과했습니다: {label}")

    def wait_command(self, component, timeout):
        path = self.component_path(component, ".exit.json")
        self.wait(path.exists, timeout, component, component)
        code = read_json(path)["code"]
        if code:
            relative = self.component_path(component, ".log").relative_to(self.root)
            raise DemoError(f"하위 명령 실패: {component} (코드 {code}), 기록: {relative}")

    def backend_ready(self):
        record = self.state["processes"].get("backend", {})
        body = http_json(f"http://127.0.0.1:{self.config.values['MANAGEMENT_PORT']}/actuator/health/readiness")
        return (body and body.get("status") == "UP" and identity(record.get("pid")) == record and
                listener_owned(8080, record) and listener_owned(int(self.config.values["MANAGEMENT_PORT"]), record))

    def frontend_ready(self):
        record = self.state["processes"].get("frontend", {})
        if identity(record.get("pid")) != record or not listener_owned(3000, record):
            return False
        direct = http_json("http://127.0.0.1:8080/departments")
        proxied = http_json("http://127.0.0.1:3000/api/backend/departments")
        page = http_bytes("http://127.0.0.1:3000/login")
        return bool(direct and proxied and direct.get("success") is True and proxied.get("success") is True and
                    proxied.get("data") == direct.get("data") and page and b"DocGrid" in page)

    def start(self, scope, timeout):
        self.check_tools()
        self.prepare_files()
        self.config = LocalConfig.load(self.root)
        self.config.ensure_local_engine()
        if len(self.config.values.get("JWT_SECRET", "").encode()) < 32:
            raise DemoError("기존 환경 파일에 32바이트 이상의 JWT_SECRET을 준비하세요.")
        if self.load() and self.state["status"] == "READY":
            if self.state["scope"] != scope or not self.valid_configuration():
                raise DemoError("활성 세션과 요청 범위·설정이 다릅니다. 기존 세션 상태를 확인하세요.")
            self.assert_ready()
            self.event("[재사용] 이미 준비된 시연 세션입니다. Web: http://localhost:3000")
            return 0
        if self.state and self.state["status"] != "STOPPED":
            if self.stop() != 0:
                raise DemoError("이전 실행을 안전하게 정리하지 못했습니다.")
            self.config = LocalConfig.load(self.root)
            self.config.ensure_local_engine()
        for port in [8080, 3000, int(self.config.values["MANAGEMENT_PORT"])]:
            if port_open(port):
                raise DemoError(f"식별되지 않은 프로세스가 필요한 포트를 사용 중입니다: {port}")
        self.config.preflight_containers()
        before = self.snapshot()
        engine = self.config.command(["docker", "info", "--format", "{{.ID}}"])
        if engine.returncode or not engine.stdout.strip():
            raise DemoError("Docker Engine 식별자를 확인할 수 없습니다.")
        self.state = {"id": uuid.uuid4().hex, "status": "STARTING", "scope": scope, "createdAt": now(),
                      "configHash": config_fingerprint(self.config), "sourceHash": source_fingerprint(self.root),
                      "engineHash": digest(engine.stdout.strip()),
                      "endpoint": endpoint_reference(self.config.values["DOCKER_HOST"]),
                      "before": before, "containers": {}, "processes": {}, "timeout": timeout,
                      "managementPort": int(self.config.values["MANAGEMENT_PORT"])}
        self.save()
        try:
            # 1. 기존 의존성을 검사하고 없는 경우에만 최초 npm ci를 연결한다.
            self.event(f"[시작] 로컬 시연 세션 {self.state['id']} · 범위 {scope}")
            if not (self.root / "frontend/node_modules").exists():
                self.spawn("install")
                self.wait_command("install", timeout)
            elif self.config.command(["npm", "--prefix", "frontend", "ls", "--depth=0"], timeout=60).returncode:
                raise DemoError("기존 프런트엔드 의존성이 맞지 않습니다. npm ci로 준비한 뒤 재시도하세요.")
            # 2. 기존 서비스 스크립트로 준비하고 컨테이너 전후 상태를 기록한다.
            self.spawn("services")
            self.wait_command("services", timeout + 30)
            self.reconcile_containers()
            # 3. 기존 공식 명령을 전용 그룹에서 실행하고 실제 수신 프로세스를 대조한다.
            self.spawn("backend")
            self.wait(self.backend_ready, timeout, "백엔드", "backend")
            self.spawn("frontend")
            self.wait(self.frontend_ready, timeout, "프런트엔드 API 프록시", "frontend")
            self.state["status"] = "READY"
            self.state["readyAt"] = now()
            self.save()
            self.event("[준비 완료] Web: http://localhost:3000 · verify로 예제 시연을 검사하세요.")
            return 0
        except BaseException:
            self.event("[기동 중단] 이번 실행이 시작한 자원을 정리합니다.")
            try:
                self.reconcile_containers()
            except (ConfigError, DemoError):
                self.event("[조치 필요] 일부 컨테이너의 소유권을 확인하지 못했습니다.")
            self.stop()
            raise

    def valid_configuration(self):
        return (config_fingerprint(self.config) == self.state["configHash"] and
                source_fingerprint(self.root) == self.state["sourceHash"])

    def assert_ready(self):
        if not self.valid_configuration() or not self.backend_ready() or not self.frontend_ready():
            raise DemoError("세션 설정·소스·실제 서버 소유권이 달라 자동 검증을 거부합니다.")
        current = self.snapshot()
        if any(not current[s] or current[s]["id"] != self.state["containers"].get(s, {}).get("id") for s in SERVICES):
            raise DemoError("세션의 Compose 대상이 변경돼 자동 업로드를 거부합니다.")
        self.config.check_database()

    def verify(self, timeout):
        if not self.load() or self.state["status"] != "READY":
            raise DemoError("먼저 start로 준비된 세션을 만드세요.")
        self.config = LocalConfig.load(self.root)
        self.config.ensure_local_engine()
        self.assert_ready()
        # 4. 프런트엔드 프록시를 경유하는 기존 검증 도구에만 계정 비밀번호를 전달한다.
        password = seed.read_password(self.config.values.get("DOCGRID_DEMO_EMAIL", "demo@docgrid.local"))
        self.config.values["DOCGRID_DEMO_PASSWORD"] = password
        report = ".demo/reports/" + uuid.uuid4().hex
        context = digest([self.state["engineHash"], self.state["containers"]["postgres"]["id"]])
        self.state["verification"] = {"id": uuid.uuid4().hex, "report": report, "context": context, "timeout": timeout}
        previous = self.directory / "last-report.json"
        if previous.exists():
            item = read_json(previous)
            if item.get("context") == context:
                self.state["verification"]["previous"] = item["report"]
        self.save()
        self.spawn("verify")
        try:
            self.wait_command("verify", timeout + 180)
        except BaseException:
            self.stop_process("verify")
            self.event("[검증 실패·중단] 필수 단계가 완료되지 않았습니다. 서버는 상태 확인 후 stop으로 종료하세요.")
            raise
        summary = read_json(self.root / report / "summary.json")
        if summary.get("exitCode") != 0:
            raise DemoError("필수 검증이 통과하지 않았습니다.")
        write_json(previous, {"context": context, "report": report + "/summary.json"})
        self.event(f"[검증 통과] 업로드·인덱싱·검색·원본 검사 완료. 기록: {report}/summary.json")
        return 0

    def stop_process(self, name):
        record = self.state.get("processes", {}).get(name)
        if name == "verify" and not self.state.get("verification"):
            return True
        pid_file = self.component_path(name, ".pid.json")
        if pid_file.exists():
            record = read_json(pid_file)
            if not isinstance(record, dict):
                return False
        if record is None:
            return True
        if (type(record.get("pid")) is not int or record["pid"] <= 1 or
            record.get("pgid") != record["pid"] or not record.get("started") or not record.get("commandHash")):
            return False
        current = identity(record.get("pid"))
        if current is None:
            handle = self.handles.get(name)
            if handle and handle.poll() is not None:
                handle.wait()
            return not group_members(record.get("pgid"))
        if current != record or record["pgid"] != record["pid"]:
            self.event(f"[조치 필요] 식별정보가 달라 자동 종료하지 않습니다: {name}")
            return False
        members = {pid: identity(pid) for pid in group_members(record["pgid"])}
        os.killpg(record["pgid"], signal.SIGTERM)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and group_members(record["pgid"]):
            time.sleep(0.2)
        remaining = group_members(record["pgid"])
        if remaining:
            if not all(pid in members and identity(pid) == members[pid] for pid in remaining):
                return False
            os.killpg(record["pgid"], signal.SIGKILL)
            time.sleep(0.2)
        released = not group_members(record["pgid"])
        handle = self.handles.get(name)
        if released and handle:
            handle.wait(timeout=2)
        return released

    def stop(self):
        if not self.state and not self.load():
            print("[종료] 실행 세션이 없습니다.")
            return 0
        if self.state["status"] == "STOPPED":
            self.event("[종료] 이미 종료된 세션입니다.")
            return 0
        released = True
        if self.state["status"] == "STARTING":
            try:
                candidate = LocalConfig.load(self.root)
                candidate.ensure_local_engine()
                if config_fingerprint(candidate) != self.state["configHash"]:
                    raise DemoError("중단된 기동의 설정이 변경됐습니다.")
                self.config = candidate
                self.reconcile_containers()
            except (ConfigError, DemoError, KeyError):
                released = False
                self.event("[조치 필요] 중단된 기동의 컨테이너 전후 상태를 확인하지 못했습니다.")
        for name in ["verify", "frontend", "backend", "services", "install"]:
            try:
                released = self.stop_process(name) and released
            except (DemoError, OSError, ValueError, KeyError):
                released = False
                self.event(f"[조치 필요] 소유 프로세스 종료를 확인하지 못했습니다: {name}")
        # Docker 종료는 저장된 endpoint·Engine ID·컨테이너 ID·작업공간을 모두 다시 대조한다.
        reference = self.state["endpoint"]
        socket_path = (Path.home() / reference["path"] if reference["base"] == "home" else Path(reference["path"]))
        env = {k:v for k,v in os.environ.items() if k != "DOCKER_CONTEXT"}
        env["DOCKER_HOST"] = "unix://" + str(socket_path)
        try:
            engine = subprocess.run(["docker", "info", "--format", "{{.ID}}"], env=env,
                                    capture_output=True, text=True, timeout=15)
            same_engine = engine.returncode == 0 and digest(engine.stdout.strip()) == self.state["engineHash"]
        except (OSError, subprocess.TimeoutExpired):
            same_engine = False
        for service, item in self.state.get("containers", {}).items():
            if not item.get("owned"):
                continue
            if not same_engine:
                released = False
                continue
            try:
                if service not in SERVICES or not re.fullmatch(r"[a-f0-9]{64}", item["id"]):
                    raise DemoError("기록된 컨테이너 식별자가 잘못됐습니다.")
                inspected = subprocess.run(["docker", "inspect", item["id"]], env=env,
                                           capture_output=True, text=True, timeout=15)
                if inspected.returncode:
                    released = False
                    continue
                data = json.loads(inspected.stdout)[0]
                labels = data["Config"].get("Labels") or {}
                if (data["Id"] != item["id"] or labels.get("com.docker.compose.service") != service or
                    Path(labels.get("com.docker.compose.project.working_dir", "")).resolve() != self.root):
                    released = False
                    continue
                result = subprocess.run(["docker", "stop", "--time", "15", item["id"]], env=env,
                                        capture_output=True, text=True, timeout=25)
                checked = subprocess.run(["docker", "inspect", item["id"]], env=env,
                                         capture_output=True, text=True, timeout=15)
                stopped = checked.returncode == 0 and not json.loads(checked.stdout)[0]["State"]["Running"]
                released = released and result.returncode == 0 and stopped
            except (DemoError, OSError, ValueError, KeyError, IndexError, subprocess.TimeoutExpired):
                released = False
                self.event(f"[조치 필요] 소유 컨테이너의 종료를 확인하지 못했습니다: {service}")
        for name, port in [("backend", 8080), ("backend", self.state.get("managementPort", 8081)),
                           ("frontend", 3000)]:
            if name in self.state.get("processes", {}) and port_open(port):
                released = False
        self.state["status"] = "STOPPED" if released else "NEEDS_ATTENTION"
        self.state["stoppedAt"] = now()
        self.save()
        self.event("[종료 완료] 소유 서버·컨테이너 중지, 데이터·원본·모델 캐시 보존" if released else
                   "[조치 필요] 종료를 확인하지 못한 자원이 있습니다. 다른 프로세스를 강제로 종료하지 않았습니다.")
        reused = sum(not item.get("owned") for item in self.state.get("containers", {}).values())
        if reused:
            self.event(f"[재사용 유지] 실행 전에 켜져 있던 Compose 서비스 {reused}개")
        if self.state["scope"] == "rag":
            self.event("[재사용 유지] Native Ollama는 외부 관리 서버로 유지합니다.")
        return 0 if released else 1

    def status(self):
        if not self.load():
            print("[상태] 실행 세션이 없습니다.")
            return 0
        print(f"[기록된 상태] {self.state['status']} · 범위 {self.state['scope']}")
        print(f"[기록] .demo/launcher/sessions/{self.state['id']}/")
        for name in ["backend", "frontend"]:
            record = self.state.get("processes", {}).get(name)
            print(f"[{name}] {'소유 프로세스 확인' if record and identity(record.get('pid')) == record else '미실행·확인 필요'}")
        if self.state["status"] == "READY":
            try:
                self.config = LocalConfig.load(self.root)
                self.config.ensure_local_engine()
                self.assert_ready()
            except (ConfigError, DemoError, OSError, KeyError):
                print("[현재 상태] 준비 확인 실패·설정 변경. verify를 성공으로 처리하지 않습니다.")
                return 1
            print("[현재 상태] 서버·API 프록시·대상 DB 준비 확인")
        return 0 if self.state["status"] in {"READY", "STOPPED"} else 1


def supervise(runtime, session_id, component):
    """전용 그룹에 남아 기존 명령 출력을 저장 전에 정제하고 자식의 수명을 관리한다."""
    directory = runtime.session_path(session_id)
    state = read_json(directory / "state.json")
    runtime.state = state
    if (state.get("id") != session_id or state.get("status") not in {"STARTING", "READY"} or
            component not in {"install", "services", "backend", "frontend", "verify"}):
        raise DemoError("소유 세션과 실행 구성요소가 일치하지 않습니다.")
    write_json(runtime.component_path(component, ".pid.json"), identity(os.getpid()))
    code = 2
    with runtime.component_path(component, ".log").open("x", encoding="utf-8") as output:
        try:
            config = LocalConfig.load(runtime.root)
            config.ensure_local_engine()
            for key, value in config.values.items():
                if any(word in key for word in ["PASSWORD", "SECRET", "TOKEN", "KEY"]) and value:
                    seed.SECRET_VALUES.append(value)
            commands = {
                "install": ["npm", "--prefix", "frontend", "ci"],
                "services": ["bash", "scripts/local-services.sh", "start", "--scope", state["scope"]],
                "backend": [str(runtime.root / "backend/gradlew"), "-p", "backend", "--no-daemon", "bootRun"],
                "frontend": ["npm", "--prefix", "frontend", "run", "dev"],
            }
            if component == "verify":
                check = state["verification"]
                report = runtime.root / check["report"]
                if runtime.root / ".demo/reports" not in report.resolve().parents:
                    raise DemoError("검증 결과 경로가 잘못됐습니다.")
                command = [sys.executable, "scripts/demo/seed_demo_corpus.py", "--base-url",
                    "http://127.0.0.1:3000/api/backend", "--embedding-url", config.values["EMBEDDING_SERVER_URL"],
                    "--scope", state["scope"], "--report-dir", str(report), "--context-id", check["context"],
                    "--timeout", str(check["timeout"])]
                if check.get("previous"):
                    previous = (runtime.root / check["previous"]).resolve()
                    if runtime.root / ".demo/reports" not in previous.parents:
                        raise DemoError("이전 원장 경로가 잘못됐습니다.")
                    command += ["--previous-report", str(previous)]
            else:
                command = commands[component]
            config.values["LOCAL_SERVICES_WAIT_TIMEOUT_SECONDS"] = str(state["timeout"])
            child = subprocess.Popen(command, cwd=runtime.root, env=config.values, stdin=subprocess.DEVNULL,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                     encoding="utf-8", errors="replace")
            for line in child.stdout:
                line = line.replace(str(runtime.root), "<작업공간>").replace(str(Path.home()), "<사용자 홈>")
                output.write(now() + " " + seed.safe_text(line))
                output.flush()
            code = child.wait()
        except Exception as error:
            output.write(now() + " 하위 실행 오류: " + type(error).__name__ + "\n")
    write_json(runtime.component_path(component, ".exit.json"), {"code": code, "finishedAt": now()})
    return code


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "_serve":
        return supervise(DemoRuntime(), sys.argv[2], sys.argv[3])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["start", "verify", "status", "stop"])
    parser.add_argument("--scope", choices=["search", "rag"])
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 3600:
        parser.error("timeout은 1~3600초여야 합니다.")
    if args.scope is not None and args.command != "start":
        parser.error("scope는 start에서만 지정하며 verify는 세션 범위를 이어받습니다.")
    runtime = DemoRuntime()
    def cancelled(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, cancelled)
    try:
        if args.command == "status":
            return runtime.status()
        with runtime.lock():
            if args.command == "start":
                return runtime.start(args.scope or "search", args.timeout)
            if args.command == "verify":
                return runtime.verify(args.timeout)
            return getattr(runtime, args.command)()
    except (DemoError, ConfigError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        print("[실패] " + seed.safe_text(str(error) if isinstance(error, (DemoError, ConfigError)) else type(error).__name__),
              file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("[중단] 실행을 취소했습니다.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
