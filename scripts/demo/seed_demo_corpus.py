#!/usr/bin/env python3
"""예제 업로드·인덱싱·검색·원본을 검증하고 선택한 범위의 결과로 종료한다.

examples/demo-corpus/manifest.json의 문서를 PUBLIC으로 업로드한다. 파일 해시로 만든
Idempotency-Key를 보내므로 같은 계정으로 다시 실행해도 문서가 중복 생성되지 않는다.
계정이 없으면 회원가입 후 로그인한다. search 범위에서는 생성 모델을 검증하지 않는다.
실행별 원장에는 안전한 상태·건수·식별자만 기록하며 응답 본문과 자격증명을 보존하지 않는다.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from datetime import datetime, timedelta, timezone

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CORPUS_DIR = REPO_ROOT / "examples" / "demo-corpus"
DEFAULT_QUERY = "재택근무는 일주일에 며칠까지 할 수 있고 언제까지 신청해야 하나요?"
CONTENT_TYPES = {
    ".pdf": "application/pdf",
    ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".hwpx": "application/hwp+zip",
    ".hwp": "application/x-hwp",
}
KEY_PREFIX = "docgrid-demo-"
UPLOAD_IN_PROGRESS = "DOCUMENT-UPLOAD-004"
UPLOAD_KEY_CONFLICT = "DOCUMENT-UPLOAD-003"
INVALID_CREDENTIALS = "USER-004"
EMAIL_ALREADY_EXISTS = "USER-002"
UPLOAD_RETRY_LIMIT = 6
UPLOAD_RETRY_DELAY_SECONDS = 5
STALL_HINT_SECONDS = 90
ACTIVE_REPORT = None
SECRET_VALUES = []
STAGE_LABELS = {"upload": "업로드", "indexing": "인덱싱", "search": "검색",
                "download": "원본 해시", "rag": "생성 답변·인용", "replay": "문서 ID 재사용"}


class SeedError(Exception):
    """사용자가 바로 조치할 수 있는 실패 안내를 담는다."""


def safe_text(message: str) -> str:
    """터미널·파일에 기록하기 전에 자격증명·이메일·서버 주소를 제거한다."""
    for value in SECRET_VALUES:
        if value:
            message = message.replace(value, "<비밀값 생략>")
    message = re.sub(r"https?://[^\s)]+", "<설정된 서버>", message)
    message = re.sub(r"[\w.+%-]+@[\w.-]+\.[\w.-]+", "<이메일 생략>", message)
    message = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "<토큰 생략>", message)
    return message


def log(message: str) -> None:
    safe = safe_text(message)
    print(safe, flush=True)
    if ACTIVE_REPORT:
        ACTIVE_REPORT.event(safe)


class DemoReport:
    """한 실행의 단계 판정·안전한 이벤트·문서 원장만 독립된 디렉터리에 보존한다."""

    def __init__(self, args):
        self.run_id = datetime.now(timezone(timedelta(hours=9))).strftime("%Y%m%dT%H%M%S%f%z")
        self.directory = args.report_dir or REPO_ROOT / ".demo" / "reports" / self.run_id
        self.directory.mkdir(parents=True, exist_ok=False)
        self.events = (self.directory / "events.jsonl").open("x", encoding="utf-8")
        self.started = time.monotonic()
        try:
            revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT,
                                               stderr=subprocess.DEVNULL, text=True).strip()
        except (OSError, subprocess.CalledProcessError):
            revision = "unknown"
        self.data = {"schemaVersion": 1, "runId": self.run_id, "revision": revision,
                     "scope": args.scope, "startedAt": self.timestamp(), "stages": {},
                     "documents": {}, "queries": []}
        for name in STAGE_LABELS:
            self.stage(name, "NOT_RUN", 0, 0)

    @staticmethod
    def timestamp():
        return datetime.now(timezone(timedelta(hours=9))).isoformat(timespec="milliseconds")

    def event(self, message):
        self.events.write(json.dumps({"timestamp": self.timestamp(), "message": message},
                                     ensure_ascii=False) + "\n")
        self.events.flush()

    def stage(self, name, status, passed, expected):
        self.data["stages"][name] = {"status": status, "passed": passed, "expected": expected}

    def finish(self, exit_code):
        self.data.update(exitCode=exit_code, finishedAt=self.timestamp(),
                         elapsedSeconds=round(time.monotonic() - self.started, 3))
        with (self.directory / "summary.json").open("x", encoding="utf-8") as target:
            json.dump(self.data, target, ensure_ascii=False, indent=2)
        self.events.close()

    def required_passed(self, compare_previous):
        required = ["upload", "indexing", "search", "download"]
        if self.data["scope"] == "rag":
            required.append("rag")
        if compare_previous:
            required.append("replay")
        return all(self.data["stages"][name]["status"] == "PASS" for name in required)


def elapsed_text(seconds: float) -> str:
    if seconds < 60:
        return f"{seconds:.1f}초"
    minutes, rest = divmod(int(round(seconds)), 60)
    return f"{minutes}분 {rest}초"


def idempotency_key(data: bytes) -> str:
    """같은 파일 바이트는 언제나 같은 키가 되도록 SHA-256을 그대로 쓴다."""
    return KEY_PREFIX + hashlib.sha256(data).hexdigest()


def load_manifest(corpus_dir: Path) -> list[dict]:
    manifest_path = corpus_dir / "manifest.json"
    try:
        documents = json.loads(manifest_path.read_text(encoding="utf-8"))["documents"]
    except (OSError, ValueError, KeyError) as error:
        raise SeedError(f"예제 문서 목록을 읽을 수 없습니다: {manifest_path} ({error})") from None
    for document in documents:
        path = corpus_dir / document["file"]
        if path.suffix.lower() not in CONTENT_TYPES:
            raise SeedError(f"지원하지 않는 확장자입니다: {document['file']}")
        if not path.is_file():
            raise SeedError(f"예제 문서 파일이 없습니다: {path}")
    if not documents or len({d["file"] for d in documents}) != len(documents):
        raise SeedError("예제 목록은 비어 있지 않아야 하며 파일명이 중복되면 안 됩니다.")
    return documents


def multipart_body(fields: dict[str, str], filename: str, content_type: str,
                   data: bytes) -> tuple[bytes, str]:
    boundary = "docgrid-demo-" + uuid.uuid4().hex
    chunks = []
    for name, value in fields.items():
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'
                      .encode("utf-8") + value.encode("utf-8") + b"\r\n")
    chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                  f'filename="{filename}"\r\nContent-Type: {content_type}\r\n\r\n'
                  .encode("utf-8") + data + b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(chunks), "multipart/form-data; boundary=" + boundary


class DocGridClient:
    """DocGrid REST API 호출과 공통 응답(ApiResponse·ErrorResponse) 해석만 담당한다."""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.token: str | None = None

    def call_bytes(self, method: str, path: str, body: bytes | None = None,
                   headers: dict[str, str] | None = None, timeout: float = 60,
                   limit: int = 2 * 1024 * 1024) -> tuple[int, bytes]:
        outgoing = dict(headers or {})
        if self.token:
            outgoing["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base_url + path, data=body, method=method,
                                         headers=outgoing)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status, raw = response.status, response.read(limit + 1)
        except urllib.error.HTTPError as error:
            with error:
                status, raw = error.code, error.read(limit + 1)
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            raise SeedError(
                "Backend에 연결할 수 없습니다.\n"
                "  → 빠른 실행 5단계의 `./backend/gradlew -p backend bootRun`이 떠 있는지, "
                "--base-url(DOCGRID_BASE_URL)이 맞는지 확인하세요."
            ) from None
        if len(raw) > limit:
            raise SeedError("응답 크기가 검증 상한을 초과했습니다.")
        return status, raw

    def call(self, method: str, path: str, body: bytes | None = None,
             headers: dict[str, str] | None = None, timeout: float = 60) -> tuple[int, dict]:
        status, raw = self.call_bytes(method, path, body, headers, timeout)
        try:
            payload = json.loads(raw) if raw else {}
        except ValueError:
            payload = {}
        return status, payload if isinstance(payload, dict) else {}

    def call_json(self, method: str, path: str, payload: dict) -> tuple[int, dict]:
        return self.call(method, path, json.dumps(payload).encode("utf-8"),
                         {"Content-Type": "application/json"})


def error_text(payload: dict) -> str:
    # 서버의 임의 오류 본문에 개인정보·인증정보가 들어갈 수 있어 코드만 허용한다.
    code = str(payload.get("code") or "")
    return code if re.fullmatch(r"[A-Z0-9._-]{1,80}", code) else "응답 상세 생략"


def authenticate(client: DocGridClient, email: str, password: str, name: str,
                 department_id: int) -> None:
    """로그인하고, 계정이 없으면 회원가입 후 다시 로그인한다."""
    credentials = {"email": email, "password": password}
    status, payload = client.call_json("POST", "/auth/login", credentials)
    if status == 401 and payload.get("code") == INVALID_CREDENTIALS:
        status, payload = client.call_json("POST", "/auth/signup", {
            **credentials, "name": name, "departmentId": department_id,
        })
        if status == 409 and payload.get("code") == EMAIL_ALREADY_EXISTS:
            raise SeedError("계정은 이미 있지만 비밀번호가 다릅니다. "
                            "DOCGRID_DEMO_PASSWORD 또는 --email을 확인하세요.")
        if status != 201:
            raise SeedError(f"회원가입 실패(HTTP {status}): {error_text(payload)} "
                            "(비밀번호는 12~64자여야 합니다)")
        log("[계정] 회원가입 완료")
        status, payload = client.call_json("POST", "/auth/login", credentials)
    token = (payload.get("data") or {}).get("accessToken")
    if status != 200 or not token:
        raise SeedError(f"로그인 실패(HTTP {status}): {error_text(payload)}")
    client.token = token
    SECRET_VALUES.append(token)
    log("[계정] 로그인 완료")


def upload(client: DocGridClient, corpus_dir: Path, document: dict) -> dict:
    path = corpus_dir / document["file"]
    data = path.read_bytes()
    body, content_type = multipart_body(
        {"title": document["title"], "description": document.get("description", ""),
         "visibility": "PUBLIC"},
        path.name, CONTENT_TYPES[path.suffix.lower()], data,
    )
    headers = {"Content-Type": content_type, "Idempotency-Key": idempotency_key(data)}
    for _ in range(UPLOAD_RETRY_LIMIT):
        status, payload = client.call("POST", "/api/documents", body, headers)
        if status == 201:
            return payload["data"]
        if status == 409 and payload.get("code") == UPLOAD_IN_PROGRESS:
            time.sleep(UPLOAD_RETRY_DELAY_SECONDS)
            continue
        if status == 409 and payload.get("code") == UPLOAD_KEY_CONFLICT:
            raise SeedError("같은 파일이 다른 제목·설명으로 이 계정에 이미 업로드되어 있습니다")
        raise SeedError(f"HTTP {status} {error_text(payload)}")
    raise SeedError("같은 업로드가 계속 진행 중으로 응답합니다")


def classify(status_data: dict) -> str:
    """문서 상태 응답을 INDEXED·FAILED·PROCESSING·PENDING 중 하나로 줄인다."""
    processing = status_data.get("processingVersion")
    if status_data.get("documentStatus") == "FAILED":
        return "FAILED"
    if processing is None:
        current = status_data.get("currentVersion") or {}
        return "INDEXED" if current.get("status") == "INDEXED" else "PENDING"
    if processing.get("jobStatus") in ("FAILED", "CANCELED") or processing.get("status") == "FAILED":
        return "FAILED"
    if processing.get("jobStatus") == "PENDING" and processing.get("status") == "UPLOADED":
        return "PENDING"
    return "PROCESSING"


def embedding_ready(embedding_url: str) -> bool:
    try:
        with urllib.request.urlopen(embedding_url.rstrip("/") + "/health/ready", timeout=3) as response:
            return response.status == 200
    except (urllib.error.URLError, TimeoutError, ConnectionError):
        return False


def diagnose(states: dict[int, str], ever_started: set[int], ready: bool, embedding_url: str) -> list[str]:
    """완료되지 않았거나 실패한 문서 상태와 임베딩 서버 준비 여부로 원인 후보를 우선순위대로 만든다."""
    waiting = [doc_id for doc_id, state in states.items() if state in ("PENDING", "PROCESSING")]
    if not waiting and "FAILED" not in states.values():
        return []
    hints = []
    if not ready:
        hints.append(f"임베딩 서버({embedding_url})가 응답하지 않습니다. "
                     f"`curl -f {embedding_url.rstrip('/')}/health/ready`와 "
                     "`docker compose logs -f embedding-server`로 상태를 확인하세요. "
                     "Worker는 Job마다 최대 3회까지 자동 재시도하며, 그 안에 복구되지 않으면 실패로 끝납니다.")
    if waiting and not ever_started.intersection(waiting):
        hints.append("인덱싱 Worker가 Job을 가져가지 않고 있습니다. `.env`나 실행 환경에 "
                     "`INDEXING_WORKER_ENABLED=false`가 있거나 `SPRING_PROFILES_ACTIVE`가 local이 "
                     "아니면 Worker가 꺼집니다(local 프로필만 기본 true). Backend 시작 로그에 "
                     "'인덱싱 Worker를 등록했습니다'가 있는지 확인하세요.")
    elif waiting and ready:
        hints.append("Worker와 임베딩 서버는 동작하지만 처리가 느릴 수 있습니다. CPU 환경에서는 "
                     "BGE-M3 임베딩이 오래 걸릴 수 있으니 --timeout을 늘려 다시 실행하세요. "
                     "다시 실행해도 이미 올린 문서는 중복 생성되지 않습니다.")
    return hints


def wait_for_indexing(client: DocGridClient, uploaded: dict[int, str], timeout: float,
                      poll_interval: float, embedding_url: str) -> tuple[dict[int, str], set[int]]:
    """모든 문서가 INDEXED·FAILED가 되거나 상한에 닿을 때까지 상태를 조회한다."""
    states = {doc_id: "PENDING" for doc_id in uploaded}
    ever_started: set[int] = set()
    started = time.monotonic()
    last_change = started
    last_summary = None
    stall_reported = False
    while True:
        for doc_id in [d for d, s in states.items() if s not in ("INDEXED", "FAILED", "DELETED")]:
            status, payload = client.call("GET", f"/api/documents/{doc_id}/status")
            if status == 404:
                states[doc_id] = "DELETED"
                continue
            if status != 200:
                raise SeedError(f"인덱싱 상태 조회 실패(문서 {doc_id}, HTTP {status}): {error_text(payload)}")
            state = classify(payload.get("data") or {})
            if state != "PENDING":
                ever_started.add(doc_id)
            states[doc_id] = state

        counts = {name: sum(1 for s in states.values() if s == name)
                  for name in ("INDEXED", "PROCESSING", "PENDING", "FAILED", "DELETED")}
        now = time.monotonic()
        summary = tuple(counts.values())
        if summary != last_summary:
            last_summary, last_change = summary, now
            log(f"[인덱싱] INDEXED {counts['INDEXED']}/{len(states)} · 처리 중 {counts['PROCESSING']} "
                f"· 대기 {counts['PENDING']} · 실패 {counts['FAILED'] + counts['DELETED']} "
                f"({elapsed_text(now - started)})")
        if counts["PROCESSING"] + counts["PENDING"] == 0:
            break
        if now - started >= timeout:
            log(f"[인덱싱] 대기 상한 {elapsed_text(timeout)}에 도달해 기다리기를 멈춥니다.")
            break
        if not stall_reported and now - last_change >= STALL_HINT_SECONDS:
            stall_reported = True
            log(f"[인덱싱] {STALL_HINT_SECONDS}초 동안 진행이 없습니다. 계속 기다리며 원인 후보를 안내합니다.")
            for hint in diagnose(states, ever_started, embedding_ready(embedding_url), embedding_url):
                log("  - " + hint)
        time.sleep(poll_interval)
    return states, ever_started


def example_search(client: DocGridClient, query: str, rag_wait: float,
                   scope: str = "search", expected_id: int | None = None,
                   top_k: int = 3) -> dict:
    """검색의 실제 결과와 선택 RAG만 판정하며 임의 응답 본문은 출력하지 않는다."""
    verdict = {"search": "FAIL", "rag": "NOT_RUN", "queryId": None}
    status, payload = client.call_json("POST", "/search", {"queryText": query, "topK": top_k})
    if status != 200:
        log(f"[검색] 실패(HTTP {status})")
        return verdict
    data = payload.get("data") or {}
    results = data.get("results") or []
    query_id = data.get("queryId")
    verdict["queryId"] = query_id if type(query_id) is int and query_id > 0 else None
    valid_results = bool(results) and all(isinstance(item, dict) and
        type(item.get("documentId")) is int and item["documentId"] > 0 and
        type(item.get("chunkId")) is int and item["chunkId"] > 0 for item in results)
    if verdict["queryId"] is None or not valid_results or (expected_id is not None and
                       expected_id not in {r.get("documentId") for r in results[:top_k]}):
        log("[검색] 실패: 빈 결과이거나 정해진 범위에 기대 문서가 없습니다.")
        return verdict
    verdict["search"] = "PASS"
    log(f"[검색] 통과: 결과 {len(results)}건, 기대 문서 포함 여부 확인")
    if scope == "search":
        log("[RAG] 미검증: search 범위에서는 생성 답변 성공을 주장하지 않습니다.")
        return verdict

    started = time.monotonic()
    while data.get("ragStatus") == "PROCESSING" and time.monotonic() - started < rag_wait:
        time.sleep(min(2, max(0, rag_wait - (time.monotonic() - started))))
        status, payload = client.call("GET", f"/search/{data.get('queryId')}")
        if status != 200:
            verdict["rag"] = "FAIL"
            return verdict
        data = payload.get("data") or {}
    candidates = {(r.get("documentId"), r.get("chunkId")) for r in results}
    citations = data.get("citations") or []
    valid_citations = bool(citations) and all(
        (c.get("documentId"), c.get("chunkId")) in candidates and
        isinstance(c.get("quotedText"), str) and bool(c["quotedText"].strip())
        for c in citations)
    verdict["rag"] = "PASS" if (data.get("ragStatus") == "SUCCESS" and
                                  bool(str(data.get("answer") or "").strip()) and
                                  valid_citations) else "FAIL"
    log(f"[RAG] {verdict['rag']}: 완료 상태·답변 존재·검색 근거 인용 검사")
    return verdict


def load_checks(args, documents):
    """질문과 topK를 실행 전에 고정하고 기대 파일을 실제 예제 목록과 대조한다."""
    if args.query:
        return 3, [{"query": args.query}]
    try:
        checks = json.loads((args.corpus_dir / "checks.json").read_text(encoding="utf-8"))
        top_k, queries = checks["topK"], checks["queries"]
        files = {d["file"] for d in documents}
        if not isinstance(top_k, int) or not 1 <= top_k <= 20 or not queries:
            raise ValueError()
        for check in queries:
            if not str(check["query"]).strip() or check["expectedFile"] not in files:
                raise ValueError()
        return top_k, queries
    except (OSError, ValueError, KeyError, TypeError):
        raise SeedError("검색 기대값 checks.json이 잘못됐습니다. 사용자 예제는 --query로 기본 검색을 확인할 수 있습니다.") from None


def verify_downloads(client, files, corpus_dir):
    """파일 API로 내려받은 바이트를 로컬 예제의 길이·SHA-256과 비교한다."""
    passed = 0
    for filename, document_id in files.items():
        expected = (corpus_dir / filename).read_bytes()
        try:
            status, actual = client.call_bytes("GET", f"/api/documents/{document_id}/file",
                                               limit=len(expected))
            matched = status == 200 and len(actual) == len(expected) and (
                hashlib.sha256(actual).digest() == hashlib.sha256(expected).digest())
        except SeedError:
            matched = False
        passed += int(matched)
        log(f"[원본] 문서 {document_id}: {'PASS' if matched else 'FAIL'}")
    return passed


def verify_searches(client, checks, files, args, top_k, report):
    """기대 문서와 선택 본문 조건을 검사하고 질문별 결과를 안전한 원장에 남긴다."""
    search_passed, rag_passed = 0, 0
    for number, check in enumerate(checks, 1):
        expected_id = files.get(check.get("expectedFile"))
        if check.get("expectedFile") and expected_id is None:
            result = {"search": "NOT_RUN", "rag": "NOT_RUN", "queryId": None}
        else:
            try:
                result = example_search(client, check["query"], args.rag_wait,
                                        args.scope, expected_id, top_k)
                if result["search"] == "PASS" and check.get("requiredContent"):
                    status, body = client.call("GET", f"/api/documents/{expected_id}/content")
                    content = "".join(str((body.get("data") or {}).get("content") or "").split())
                    if status != 200 or not all("".join(text.split()) in content
                                                for text in check["requiredContent"]):
                        result["search"] = "FAIL"
            except SeedError:
                result = {"search": "FAIL", "rag": "NOT_RUN", "queryId": None}
        report.data["queries"].append({"number": number, "expectedDocumentId": expected_id,
                                       **result})
        search_passed += int(result["search"] == "PASS")
        rag_passed += int(result["rag"] == "PASS")
        log(f"[질문 {number}] 검색 {result['search']} · 생성 답변 {result['rag']}")
    report.stage("search", "PASS" if search_passed == len(checks) else "FAIL",
                 search_passed, len(checks))
    if args.scope == "rag":
        report.stage("rag", "PASS" if rag_passed == len(checks) else "FAIL", rag_passed, len(checks))


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("DOCGRID_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--email", default=os.environ.get("DOCGRID_DEMO_EMAIL", "demo@docgrid.local"))
    parser.add_argument("--name", default="DocGrid 데모")
    parser.add_argument("--department-id", type=int, default=1)
    parser.add_argument("--corpus-dir", type=Path, default=DEFAULT_CORPUS_DIR)
    parser.add_argument("--timeout", type=float, default=os.environ.get("DOCGRID_SEED_TIMEOUT", "600"))
    parser.add_argument("--poll-interval", type=float, default=3)
    parser.add_argument("--embedding-url", default=os.environ.get("EMBEDDING_SERVER_URL", "http://localhost:8000"))
    parser.add_argument("--skip-embedding-check", action="store_true")
    parser.add_argument("--query", help="고정 3문항 대신 사용자 질문 1건의 비어 있지 않은 결과 확인")
    parser.add_argument("--rag-wait", type=float, default=60)
    parser.add_argument("--scope", choices=["search", "rag"], default="search")
    parser.add_argument("--report-dir", type=Path, help="새 실행 결과 디렉터리(기존 디렉터리 거부)")
    parser.add_argument("--previous-report", type=Path, help="같은 대상·계정·예제의 이전 summary.json")
    parser.add_argument("--context-id", default="", help="호출자가 제공하는 실행 대상 식별자")
    args = parser.parse_args(argv)
    for name in ("timeout", "poll_interval", "rag_wait"):
        value = getattr(args, name)
        if not math.isfinite(value) or value < 0 or (name != "rag_wait" and value == 0):
            parser.error(f"{name} 값의 범위가 잘못됐습니다.")
    return args


def read_password(email: str) -> str:
    password = os.environ.get("DOCGRID_DEMO_PASSWORD")
    if not password:
        if not sys.stdin.isatty():
            raise SeedError("비밀번호가 없습니다. DOCGRID_DEMO_PASSWORD 환경변수로 전달하세요.")
        password = getpass.getpass("예제 계정 비밀번호(12자 이상): ")
    SECRET_VALUES.append(password)
    return password


def execute(args, report):
    # 1. 검증 질문과 파일 바이트를 업로드 전에 고정한다.
    documents = load_manifest(args.corpus_dir)
    top_k, checks = load_checks(args, documents)
    hashes = {d["file"]: hashlib.sha256((args.corpus_dir / d["file"]).read_bytes()).hexdigest()
              for d in documents}
    corpus_hash = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
    # 원문 계정·주소 대신 비교에 필요한 fingerprint만 로컬 원장에 보존한다.
    target_hash = hashlib.sha256(json.dumps([args.base_url, args.email, args.context_id]).encode()).hexdigest()
    report.data.update(corpusSha256=corpus_hash, targetFingerprint=target_hash, topK=top_k,
                       checksSha256=hashlib.sha256(json.dumps(checks, sort_keys=True).encode()).hexdigest())
    previous = None
    if args.previous_report:
        previous = json.loads(args.previous_report.read_text(encoding="utf-8"))
        if previous.get("corpusSha256") != corpus_hash or previous.get("targetFingerprint") != target_hash:
            raise SeedError("이전 원장의 대상·계정·예제가 현재 실행과 일치하지 않습니다.")
    if not args.skip_embedding_check and not embedding_ready(args.embedding_url):
        raise SeedError("BGE-M3가 준비되지 않아 업로드하지 않았습니다. /health/ready를 확인하세요.")

    # 2. 기존 인증·멱등 업로드·인덱싱 대기를 그대로 사용한다.
    client = DocGridClient(args.base_url)
    authenticate(client, args.email, read_password(args.email), args.name, args.department_id)
    files, uploaded = {}, {}
    for number, document in enumerate(documents, 1):
        try:
            document_id = upload(client, args.corpus_dir, document)["documentId"]
            if type(document_id) is not int or document_id <= 0:
                raise SeedError("업로드 응답의 문서 ID가 잘못됐습니다.")
            files[document["file"]] = document_id
            uploaded[document_id] = document["title"]
            report.data["documents"][hashes[document["file"]]] = document_id
            log(f"[업로드] {number}/{len(documents)} · 문서 {document_id}")
        except SeedError:
            log(f"[업로드] {number}/{len(documents)} · FAIL")
    count = len(documents)
    report.stage("upload", "PASS" if len(uploaded) == count else "FAIL", len(uploaded), count)
    states, ever_started = wait_for_indexing(client, uploaded, args.timeout, args.poll_interval,
                                             args.embedding_url) if uploaded else ({}, set())
    indexed = sum(s == "INDEXED" for s in states.values())
    report.stage("indexing", "PASS" if indexed == count else "FAIL", indexed, count)
    for hint in diagnose(states, ever_started, embedding_ready(args.embedding_url), args.embedding_url):
        log(hint)

    # 3. 인덱싱과 별개로 검색·원본·선택 RAG의 필수 결과를 판정한다.
    if indexed:
        verify_searches(client, checks, files, args, top_k, report)
        downloads = verify_downloads(client, files, args.corpus_dir)
        report.stage("download", "PASS" if downloads == count else "FAIL", downloads, count)
    if previous:
        matches = sum(previous.get("documents", {}).get(digest) == doc_id
                      for digest, doc_id in report.data["documents"].items())
        report.stage("replay", "PASS" if matches == count else "FAIL", matches, count)
    return 0 if report.required_passed(previous is not None) else 1


def run(argv: list[str] | None = None) -> int:
    """선택 범위의 필수 단계가 모두 통과한 경우에만 0으로 종료한다."""
    global ACTIVE_REPORT
    args = parse_args(argv)
    SECRET_VALUES.clear()
    try:
        report = DemoReport(args)
    except OSError:
        raise SeedError("새 결과 디렉터리를 만들 수 없습니다. 기존 원장은 덮어쓰지 않습니다.") from None
    ACTIVE_REPORT = report
    exit_code = 2
    try:
        log(f"[실행] {report.run_id} · 범위 {args.scope}")
        exit_code = execute(args, report)
    except KeyboardInterrupt:
        exit_code = 130
        log("[중단] 필수 검증이 완료되지 않았습니다.")
    except (SeedError, OSError, ValueError, KeyError, TypeError) as error:
        report.data["errorType"] = type(error).__name__
        log(f"[오류] {error if isinstance(error, SeedError) else type(error).__name__}")
    finally:
        for name, result in report.data["stages"].items():
            log(f"[{STAGE_LABELS[name]}] {result['status']} · {result['passed']}/{result['expected']}")
        log(f"[최종] 종료 코드 {exit_code}")
        ACTIVE_REPORT = None
        report.finish(exit_code)
        SECRET_VALUES.clear()
    return exit_code


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except SeedError as error:
        log(f"[오류] {error}")
        raise SystemExit(2) from None
