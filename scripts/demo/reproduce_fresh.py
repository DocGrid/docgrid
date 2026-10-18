#!/usr/bin/env python3
"""빈 GitHub hosted 실행기에서 실제 로컬 시연과 생명주기를 재현하고 목적별 근거를 남긴다."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("demo_runtime", ROOT / "scripts/demo.py")
demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(demo)


class Reproduction:
    """개인 환경에서는 실행을 거부하며 임시 실행기의 자원·정제 근거만 관리한다."""

    def __init__(self):
        self.run_id = os.environ["GITHUB_RUN_ID"] + "-" + os.environ["GITHUB_RUN_ATTEMPT"]
        self.directory = ROOT / ".demo/reproduction" / self.run_id
        self.directory.mkdir(parents=True, exist_ok=False)
        self.password = secrets.token_urlsafe(24)
        demo.seed.SECRET_VALUES.append(self.password)
        self.environment = {**os.environ, "DOCGRID_DEMO_PASSWORD": self.password}
        self.results = []
        self.stage = "preflight"
        self.collect_stopped = threading.Event()
        self.collector = threading.Thread(target=self.collect_live, daemon=True)
        self.collector.start()

    def collect_live(self):
        """취소로 최종 정리가 중단돼도 이미 정제된 구성요소 로그의 최근 사본을 보존한다."""
        while not self.collect_stopped.wait(2):
            try:
                self.collect()
            except OSError:
                pass

    def save(self):
        demo.write_json(self.directory / "summary.json", {"runId": self.run_id,
            "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
            "environment": "GitHub hosted Ubuntu 24.04 · actual PostgreSQL/Valkey/BGE-M3 · no Ollama",
            "results": self.results})

    def record(self, name, purpose, verdict, **values):
        item = {"id": name, "purpose": purpose, "timestamp": demo.now(), "verdict": verdict, **values}
        self.results.append(item)
        self.save()
        print(json.dumps(item, ensure_ascii=False), flush=True)

    def execute(self, name, purpose, command, expected=0, timeout=1200, probe=None, extra_env=None):
        self.stage = name
        started = time.monotonic()
        path = self.directory / (name + ".log")
        with path.open("x", encoding="utf-8") as output:
            output.write(f"실행 ID: {self.run_id}/{name}\n목적: {purpose}\n시작: {demo.now()}\n")
            output.write(f"장소: 독립 GitHub hosted 실행기\n기대 종료 코드: {expected}\n")
            output.write("절차: " + " ".join(command) + "\n")
            proc = subprocess.Popen(command, cwd=ROOT, env={**self.environment, **(extra_env or {})},
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", start_new_session=True)
            def collect():
                for line in proc.stdout:
                    safe = demo.seed.safe_text(line.replace(str(ROOT), "<작업공간>").replace(str(Path.home()), "<사용자 홈>"))
                    output.write(demo.now() + " " + safe)
                    output.flush()
            reader = threading.Thread(target=collect)
            reader.start()
            try:
                if probe:
                    probe(proc)
                code = proc.wait(timeout=timeout)
            except BaseException:
                self.collect()
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=90)
                    except subprocess.TimeoutExpired:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait(timeout=10)
                raise
            finally:
                reader.join(timeout=5)
            elapsed = round(time.monotonic() - started, 3)
            output.write(f"종료: {demo.now()}\n실제 종료 코드: {code}\n소요 시간: {elapsed}초\n")
        self.record(name, purpose, "PASS" if code == expected else "FAIL", exitCode=code,
                    expectedExitCode=expected, elapsedSeconds=elapsed, log=path.name)
        if code != expected:
            raise RuntimeError("expected exit code mismatch")

    def state(self):
        runtime = demo.DemoRuntime(ROOT)
        return runtime.load()

    def wait_state(self, predicate, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = self.state()
            if state and predicate(state):
                return state
            time.sleep(0.1)
        raise RuntimeError("state wait timeout")

    def concurrent_probe(self, proc):
        self.wait_state(lambda state: state["status"] == "STARTING")
        self.execute("concurrent-start", "진행 중 start의 잠금으로 두 번째 start 거부", ["./scripts/demo.py", "start"], expected=1, timeout=30)

    def interruption_probe(self, proc):
        self.wait_state(lambda state: state["status"] == "STARTING" and "backend" in state["processes"])
        proc.send_signal(signal.SIGTERM)

    def assert_stopped(self, name):
        state = self.state()
        passed = bool(state and state["status"] == "STOPPED" and
                      not any(demo.port_open(port) for port in [8080, 8081, 3000, 8000, 6379, 55432]))
        owned = 0
        if state:
            for service, item in state["containers"].items():
                raw = subprocess.check_output(["docker", "inspect", "--format", "{{.State.Running}}", item["id"]], text=True)
                passed = passed and raw.strip() == "false"
                owned += int(item["owned"])
        self.record(name, "종료 뒤 실제 서버 포트·컨테이너 실행 상태 확인", "PASS" if passed else "FAIL", ownedContainers=owned)
        if not passed:
            raise RuntimeError("cleanup incomplete")

    def report(self, name, replay=False):
        current = demo.read_json(ROOT / ".demo/launcher/last-report.json")
        summary_path = ROOT / current["report"]
        value = demo.read_json(summary_path)
        required = {"upload": 15, "indexing": 15, "search": 3, "download": 15}
        if replay:
            required["replay"] = 15
        passed = value["exitCode"] == 0 and all(value["stages"][stage] ==
            {"status": "PASS", "passed": count, "expected": count} for stage, count in required.items())
        target = self.directory / name
        target.mkdir()
        for path in summary_path.parent.iterdir():
            if path.name in {"summary.json", "events.jsonl"}:
                shutil.copyfile(path, target / path.name)
        self.record(name, "예제 필수 단계 수와 이전 원장 문서 ID 검사", "PASS" if passed else "FAIL",
                    counts=required, rag=value["stages"]["rag"]["status"])
        if not passed:
            raise RuntimeError("demo verdict incomplete")

    def run(self):
        if (ROOT / ".env").exists() or (ROOT / "frontend/.env.local").exists():
            raise RuntimeError("fresh environment files already exist")
        existing = subprocess.check_output(["docker", "ps", "-aq"], text=True).strip()
        images = subprocess.check_output(["docker", "image", "ls", "-q"], text=True).split()
        volumes = subprocess.check_output(["docker", "volume", "ls", "-q"], text=True).split()
        if existing or volumes:
            raise RuntimeError("fresh Docker environment required")
        self.record("preflight", "독립 실행기의 새 환경 확인", "PASS", existingContainers=0,
                    existingImageCount=len(set(images)), existingVolumes=0, environmentFilesInitiallyPresent=0,
                    workload={"documents": 15, "searchQuestions": 3, "interactiveBrowserSessions": 1,
                              "noScriptBoundarySessions": 1})
        command = ["./scripts/demo.py"]
        self.execute("cold-start", "모델·의존성 최초 준비를 포함한 전체 기동", command + ["start", "--timeout", "1200"], probe=self.concurrent_probe, timeout=1800)
        paths = [ROOT / ".env", ROOT / "frontend/.env.local"]
        before = [(hashlib.sha256(p.read_bytes()).digest(), p.stat().st_mtime_ns) for p in paths]
        self.execute("verify-first", "실제 모델 인덱싱·검색·프록시·원본 대조", command + ["verify", "--timeout", "900"])
        self.report("first-result")
        self.execute("verify-replay", "같은 계정 재실행과 문서 ID 재사용", command + ["verify", "--timeout", "900"])
        self.report("replay-result", replay=True)
        self.execute("browser", "실제 Chromium 로그인·질문 전송·원본 다운로드 해시", ["node", "scripts/demo/browser_smoke.cjs", str(self.directory / "browser")], timeout=180)
        self.execute("ready-status", "기록이 아닌 실제 서버·프록시·DB 준비 재확인", command + ["status"], timeout=60)
        self.execute("reuse-start", "동일 활성 세션 중복 기동 없이 재사용", command + ["start"], timeout=60)
        self.execute("stop-first", "소유 자원 종료와 데이터·캐시 보존", command + ["stop"], timeout=120)
        self.assert_stopped("stop-observation")
        self.execute("stop-replay", "이미 종료된 세션 재종료", command + ["stop"], timeout=30)
        self.execute("missing-provider", "잘못 지정된 임베딩 주소는 준비 실패로 판정", command + ["start", "--timeout", "5"], expected=1, timeout=120,
                     extra_env={"EMBEDDING_SERVER_URL": "http://127.0.0.1:17999"})
        self.assert_stopped("missing-provider-cleanup")
        self.execute("interrupted-start", "실제 백엔드 기동 중 부모 SIGTERM 뒤 소유 자원 정리", command + ["start"], expected=130, timeout=180, probe=self.interruption_probe)
        self.assert_stopped("interrupted-cleanup")
        self.execute("warm-start", "보존된 의존성·모델 캐시로 재기동", command + ["start"], timeout=240)
        self.execute("verify-warm", "재기동 뒤 데이터 보존과 문서 ID 재확인", command + ["verify"], timeout=240)
        self.report("warm-result", replay=True)
        self.execute("stop-final", "최종 소유 자원 정리", command + ["stop"], timeout=120)
        self.assert_stopped("final-cleanup")
        unchanged = before == [(hashlib.sha256(p.read_bytes()).digest(), p.stat().st_mtime_ns) for p in paths]
        self.record("environment-preserved", "생성 이후 환경 파일 bytes·mtime 보존", "PASS" if unchanged else "FAIL", unchangedFiles=2 if unchanged else 0)
        if not unchanged:
            raise RuntimeError("environment file changed")

    def collect(self):
        source = ROOT / ".demo/launcher/sessions"
        if source.exists():
            for directory in source.iterdir():
                target = self.directory / "runtime" / directory.name
                target.mkdir(parents=True, exist_ok=True)
                for path in directory.iterdir():
                    if path.suffix == ".log" or path.name == "events.jsonl":
                        shutil.copyfile(path, target / path.name)


def main():
    # 개인 컴퓨터나 자체 운영 runner에서는 기존 자원 보호를 위해 실행 자체를 거부한다.
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        print("이 시험은 별도 GitHub hosted 임시 실행기 전용입니다.")
        return 2
    run = Reproduction()
    code = 0
    try:
        run.run()
    except Exception as error:
        run.record("incomplete", "현재 회차의 완료 여부", "FAIL", at=run.stage, errorType=type(error).__name__)
        code = 1
    finally:
        try:
            run.execute("always-cleanup", "시험 중단·완료 후 소유 자원 종료", ["./scripts/demo.py", "stop"], timeout=120)
        except Exception:
            code = 1
        run.collect_stopped.set()
        run.collector.join(timeout=5)
        run.collect()
    return code


if __name__ == "__main__":
    raise SystemExit(main())
