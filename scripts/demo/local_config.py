"""로컬 시연의 지원 설정만 해석하고 실제 Compose 바인딩·DB를 읽기 전용으로 확인한다."""
from __future__ import annotations

import argparse
import json
import os
import re
import socket
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

LOOPBACKS = {"localhost", "127.0.0.1", "::1"}
DEFAULTS = {
    "SPRING_PROFILES_ACTIVE": "local", "STORAGE_TYPE": "local",
    "DB_HOST": "localhost", "DB_PORT": "55432", "DB_NAME": "app", "DB_USER": "app",
    "DB_SCHEMA": "public", "DB_SSLMODE": "disable", "REDIS_HOST": "localhost",
    "REDIS_PORT": "6379", "REDIS_PASSWORD": "", "SERVER_PORT": "8080", "MANAGEMENT_PORT": "8081",
    "EMBEDDING_SERVER_URL": "http://localhost:8000", "OLLAMA_SERVER_URL": "http://localhost:11434",
    "OLLAMA_MODEL": "qwen2.5:7b", "STORAGE_LOCAL_ROOT": "./data/docgrid", "STORAGE_BUCKET": "docgrid",
}


class ConfigError(Exception):
    """실제 주소·자격증명을 노출하지 않는 로컬 시연 설정 오류다."""


def read_env(path: Path, required=True) -> dict[str, str]:
    """예제의 KEY=VALUE 부분집합만 읽으며 셸 실행·치환·모호한 인용을 허용하지 않는다."""
    if not path.exists():
        if required:
            raise ConfigError("환경 파일이 없습니다. 예제 파일을 복사해 준비하세요.")
        return {}
    values = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        raise ConfigError("환경 파일을 안전하게 읽을 수 없습니다.") from None
    for number, line in enumerate(lines, 1):
        text = line
        if not text.strip() or text.lstrip().startswith("#"):
            continue
        match = re.fullmatch(r"([A-Z][A-Z0-9_]*)=(.*)", text)
        if not match:
            raise ConfigError(f"환경 파일 {number}행: 지원 형식은 KEY=VALUE입니다.")
        key, value = match.groups()
        if key in values:
            raise ConfigError(f"중복 환경변수: {key}")
        if value != value.strip() or any(c in value for c in ['"', "'", "`", "$", "\\"]) or " #" in value:
            raise ConfigError(f"모호한 인용·치환·이스케이프 설정은 지원하지 않습니다: {key}")
        values[key] = value
    return values


def local_url(value: str, key: str) -> str:
    """자격증명·원격 호스트·리다이렉트용 URL 요소가 없는 로컬 HTTP 주소만 받는다."""
    try:
        parsed = urlsplit(value)
        valid = (not any(c.isspace() for c in value) and parsed.scheme == "http" and parsed.hostname in LOOPBACKS and
                 parsed.username is None and parsed.password is None and not parsed.query and not parsed.fragment and
                 parsed.path in ("", "/") and parsed.port and 1 <= parsed.port <= 65535)
    except ValueError:
        valid = False
    if not valid:
        raise ConfigError(f"로컬 HTTP 주소만 지원합니다: {key}")
    return value.rstrip("/")


@dataclass
class LocalConfig:
    """설정 원문은 메모리 안에만 두고 서비스·런처가 같은 확정 환경을 사용하게 한다."""

    root: Path
    values: dict[str, str]
    frontend: dict[str, str]

    @classmethod
    def load(cls, root: Path, environ=None):
        root = root.resolve()
        inherited = dict(os.environ if environ is None else environ)
        values = {**DEFAULTS, **read_env(root / ".env"), **inherited}
        for key in values:
            if (key.startswith(("SPRING_DATASOURCE_", "SPRING_FLYWAY_", "SPRING_CONFIG_", "SPRING_JPA_",
                                "SPRING_SQL_", "SPRING_DATA_REDIS_", "OPENSQL_")) or
                    (key.startswith("SPRING_PROFILES_") and key != "SPRING_PROFILES_ACTIVE") or
                    key in {"SPRING_APPLICATION_JSON", "SPRING_PROFILES_INCLUDE", "SPRING_PROFILES_DEFAULT",
                            "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "GRADLE_OPTS"}) and values[key]:
                raise ConfigError(f"통합 로컬 시연에서 지원하지 않는 설정 우회입니다: {key}")
        if values["SPRING_PROFILES_ACTIVE"] != "local" or values["STORAGE_TYPE"] != "local":
            raise ConfigError("통합 시연은 local 프로필과 Local Filesystem 저장소만 지원합니다.")
        for key in ["DB_HOST", "REDIS_HOST"]:
            if values[key] not in {"localhost", "127.0.0.1"}:
                raise ConfigError(f"로컬 호스트만 지원합니다: {key}")
            # localhost의 IPv6 SSH 터널과 IPv4 Compose 포트를 혼동하지 않도록 실제 자식 설정도 고정한다.
            values[key] = "127.0.0.1"
        for key in ["DB_PORT", "REDIS_PORT", "SERVER_PORT", "MANAGEMENT_PORT"]:
            if not values[key].isdigit() or not 1 <= int(values[key]) <= 65535:
                raise ConfigError(f"포트 설정이 잘못됐습니다: {key}")
        if values["SERVER_PORT"] != "8080" or values["MANAGEMENT_PORT"] == "3000":
            raise ConfigError("통합 시연은 API 8080·Web 3000과 별도의 Management 포트를 사용합니다.")
        if values.get("INDEXING_WORKER_ENABLED", "true").lower() != "true":
            raise ConfigError("통합 시연에는 인덱싱 Worker가 필요합니다.")
        if values.get("REDIS_PASSWORD"):
            raise ConfigError("기본 로컬 Compose Valkey는 인증 없는 로컬 연결을 사용합니다.")
        if not values.get("DB_PASSWORD"):
            raise ConfigError("DB_PASSWORD를 설정하세요.")
        for key in ["DB_NAME", "DB_USER", "DB_SCHEMA", "STORAGE_BUCKET"]:
            if not re.fullmatch(r"[A-Za-z0-9_-]+", values[key]):
                raise ConfigError(f"로컬 식별자 형식이 잘못됐습니다: {key}")
        storage = (root / values["STORAGE_LOCAL_ROOT"]).resolve()
        if not any(storage == base or base in storage.parents
                   for base in [root / "data", root / ".demo" / "storage"]):
            raise ConfigError("시연 원본 경로는 현재 저장소의 data 또는 .demo/storage 내부여야 합니다.")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}", values["OLLAMA_MODEL"]):
            raise ConfigError("지정 모델 이름 형식이 잘못됐습니다.")
        for key in ["EMBEDDING_SERVER_URL", "OLLAMA_SERVER_URL"]:
            values[key] = local_url(values[key], key)
        front = {**read_env(root / "frontend" / ".env.local", required=False)}
        for key in ["BACKEND_API_URL", "NEXT_PUBLIC_BACKEND_WS_URL"]:
            front[key] = inherited.get(key, front.get(key, "http://localhost:8080"))
            local_url(front[key], key)
            if urlsplit(front[key]).port != 8080:
                raise ConfigError(f"프런트엔드와 API 8080의 대상이 다릅니다: {key}")
        values.update(front)
        values["STORAGE_LOCAL_ROOT"] = str(storage)
        return cls(root, values, front)

    def command(self, arguments, timeout=15, extra_env=None):
        """명령의 원문 오류·환경은 출력하지 않고 호출자에게만 전달한다."""
        try:
            return subprocess.run(arguments, cwd=self.root, env={**self.values, **(extra_env or {})},
                                  capture_output=True, text=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired):
            raise ConfigError("로컬 실행 도구에 연결할 수 없거나 제한 시간을 초과했습니다.") from None

    def compose(self, *arguments):
        return ["docker", "compose", "--project-directory", str(self.root),
                "-f", str(self.root / "docker-compose.yml"), *arguments]

    def ensure_local_engine(self):
        context = self.values.get("DOCKER_CONTEXT")
        endpoint = None if context else self.values.get("DOCKER_HOST")
        if not endpoint:
            result = self.command(["docker", "context", "inspect", *([context] if context else []),
                                   "--format", "{{.Endpoints.docker.Host}}"])
            endpoint = result.stdout.strip() if result.returncode == 0 else ""
        if not endpoint.startswith("unix://"):
            raise ConfigError("로컬 Unix 소켓 Docker Engine만 지원합니다.")
        # 전역 context가 도중에 바뀌어도 이후 명령은 확인한 endpoint만 사용한다.
        self.values.pop("DOCKER_CONTEXT", None)
        self.values["DOCKER_HOST"] = endpoint

    def container(self, service):
        result = self.command(self.compose("ps", "--all", "--quiet", service))
        ids = result.stdout.split() if result.returncode == 0 else []
        if len(ids) != 1:
            raise ConfigError(f"대상 Compose 컨테이너를 확인할 수 없습니다: {service}")
        inspected = self.command(["docker", "inspect", ids[0]])
        try:
            data = json.loads(inspected.stdout)[0]
            labels = data["Config"]["Labels"]
            matches = (labels.get("com.docker.compose.service") == service and
                       Path(labels.get("com.docker.compose.project.working_dir", "")).resolve() == self.root)
        except (ValueError, KeyError, IndexError, TypeError):
            matches = False
        if inspected.returncode or not matches:
            raise ConfigError(f"다른 실행 환경의 컨테이너는 사용하지 않습니다: {service}")
        return data

    def preflight_containers(self):
        """up 전에 기존 컨테이너의 출처를 확인해 다른 작업공간의 자원 교체를 방지한다."""
        if self.command(["docker", "info", "--format", "{{.ServerVersion}}"]).returncode:
            raise ConfigError("Docker Engine에 연결할 수 없습니다.")
        rendered = self.command(self.compose("config", "--format", "json"))
        if rendered.returncode:
            raise ConfigError("Compose 설정을 안전하게 해석할 수 없습니다.")
        try:
            services = json.loads(rendered.stdout)["services"]
            for service in ["postgres", "valkey", "embedding-server"]:
                name = services[service]["container_name"]
                inspected = self.command(["docker", "inspect", name])
                if inspected.returncode:
                    continue
                data = json.loads(inspected.stdout)[0]
                labels = data["Config"]["Labels"]
                if (labels.get("com.docker.compose.service") != service or
                    Path(labels.get("com.docker.compose.project.working_dir", "")).resolve() != self.root):
                    raise ConfigError(f"다른 실행 환경의 컨테이너를 교체하지 않습니다: {service}")
        except (ValueError, KeyError, TypeError, IndexError):
            raise ConfigError("기존 Compose 자원 출처를 안전하게 확인할 수 없습니다.") from None

    def check_binding(self, service, container_port, host_port):
        """루프백 주소 외에도 실제 Compose ID·프로젝트·포트 매핑을 함께 대조한다."""
        data = self.container(service)
        bindings = data.get("NetworkSettings", {}).get("Ports", {}).get(f"{container_port}/tcp") or []
        matching = [b for b in bindings if b.get("HostPort") == str(host_port) and
                    b.get("HostIp") in {"127.0.0.1", "0.0.0.0", "::", "::1", ""}]
        if not data.get("State", {}).get("Running") or not matching:
            raise ConfigError(f"앱의 대상 포트와 실행 중 컨테이너가 일치하지 않습니다: {service}")
        if service == "postgres":
            environment = dict(item.split("=", 1) for item in data["Config"].get("Env", []) if "=" in item)
            if any(environment.get(source) != self.values[target] for source, target in [
                ("POSTGRES_DB", "DB_NAME"), ("POSTGRES_USER", "DB_USER"), ("POSTGRES_PASSWORD", "DB_PASSWORD")]):
                raise ConfigError("백엔드 DB 설정과 Compose DB 초기 설정이 다릅니다.")
        if service in {"postgres", "valkey"}:
            if not any(b.get("HostIp") in {"127.0.0.1", "0.0.0.0", ""} for b in matching):
                raise ConfigError(f"IPv4 로컬 대상의 포트 바인딩이 없습니다: {service}")
            try:
                with socket.create_connection(("127.0.0.1", int(host_port)), timeout=3):
                    pass
            except OSError:
                raise ConfigError(f"앱이 접속할 로컬 포트에 연결할 수 없습니다: {service}") from None
        return data

    def check_database(self):
        self.check_binding("postgres", 5432, self.values["DB_PORT"])
        query = ("SELECT current_database(), current_user, extversion FROM pg_extension "
                 "WHERE extname='vector' AND 'vector'::regtype IS NOT NULL")
        result = self.command(self.compose("exec", "-T", "-e", "PGPASSWORD", "-e", "PGSSLMODE",
            "-e", "PGOPTIONS", "postgres", "psql", "-X",
            "-h", "127.0.0.1", "-U", self.values["DB_USER"], "-d", self.values["DB_NAME"],
            "-v", "ON_ERROR_STOP=1", "-A", "-t", "-c", query), extra_env={
                "PGPASSWORD": self.values["DB_PASSWORD"], "PGSSLMODE": self.values["DB_SSLMODE"],
                "PGOPTIONS": "-c search_path=" + self.values["DB_SCHEMA"]})
        fields = result.stdout.strip().split("|")
        if result.returncode or fields != [self.values["DB_NAME"], self.values["DB_USER"], "0.8.1"]:
            raise ConfigError("대상 DB 인증·pgvector 0.8.1 읽기 전용 점검을 통과하지 못했습니다.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["services", "database", "bindings"])
    parser.add_argument("--scope", choices=["search", "rag"], default="search")
    parser.add_argument("--service-action", choices=["start", "status"], default="status")
    args = parser.parse_args()
    try:
        config = LocalConfig.load(Path(__file__).resolve().parents[2])
        config.ensure_local_engine()
        if args.action == "services":
            if args.service_action == "start":
                config.preflight_containers()
            environment = {**config.values, "DOCGRID_LOCAL_EXPLICIT": "1", "DOCGRID_LOCAL_SCOPE": args.scope}
            os.execvpe("bash", ["bash", str(config.root / "scripts/local-services.sh"), args.service_action], environment)
        elif args.action == "database":
            config.check_database()
        else:
            config.check_binding("postgres", 5432, config.values["DB_PORT"])
            config.check_binding("valkey", 6379, config.values["REDIS_PORT"])
            config.check_binding("embedding-server", 8000, urlsplit(config.values["EMBEDDING_SERVER_URL"]).port)
        return 0
    except ConfigError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
