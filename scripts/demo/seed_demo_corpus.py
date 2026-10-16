#!/usr/bin/env python3
"""DocGrid 예제 문서를 업로드하고 인덱싱 완료와 예시 검색·RAG 답변까지 한 번에 확인한다.

examples/demo-corpus/manifest.json의 문서를 PUBLIC으로 업로드한다. 파일 해시로 만든
Idempotency-Key를 보내므로 같은 계정으로 다시 실행해도 문서가 중복 생성되지 않는다.
계정이 없으면 회원가입 후 로그인한다. 비밀번호와 Access Token은 출력하지 않는다.
"""

from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

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
SNIPPET_LENGTH = 70
ANSWER_PREVIEW_LENGTH = 300


class SeedError(Exception):
    """사용자가 바로 조치할 수 있는 실패 안내를 담는다."""


def log(message: str) -> None:
    print(message, flush=True)


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

    def call(self, method: str, path: str, body: bytes | None = None,
             headers: dict[str, str] | None = None, timeout: float = 60) -> tuple[int, dict]:
        outgoing = dict(headers or {})
        if self.token:
            outgoing["Authorization"] = "Bearer " + self.token
        request = urllib.request.Request(self.base_url + path, data=body, method=method,
                                         headers=outgoing)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            raise SeedError(
                f"Backend({self.base_url})에 연결할 수 없습니다: {getattr(error, 'reason', error)}\n"
                "  → 빠른 실행 5단계의 `./backend/gradlew -p backend bootRun`이 떠 있는지, "
                "--base-url(DOCGRID_BASE_URL)이 맞는지 확인하세요."
            ) from None
        try:
            payload = json.loads(raw) if raw else {}
        except ValueError:
            payload = {}
        return status, payload if isinstance(payload, dict) else {}

    def call_json(self, method: str, path: str, payload: dict) -> tuple[int, dict]:
        return self.call(method, path, json.dumps(payload).encode("utf-8"),
                         {"Content-Type": "application/json"})


def error_text(payload: dict) -> str:
    code, message = payload.get("code"), payload.get("message")
    return f"{code} {message}" if code else "응답 본문 없음"


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
            raise SeedError(f"{email} 계정은 이미 있지만 비밀번호가 다릅니다. "
                            "DOCGRID_DEMO_PASSWORD 또는 --email을 확인하세요.")
        if status != 201:
            raise SeedError(f"회원가입 실패(HTTP {status}): {error_text(payload)} "
                            "(비밀번호는 12~64자여야 합니다)")
        log(f"[계정] {email} 회원가입 완료")
        status, payload = client.call_json("POST", "/auth/login", credentials)
    token = (payload.get("data") or {}).get("accessToken")
    if status != 200 or not token:
        raise SeedError(f"로그인 실패(HTTP {status}): {error_text(payload)}")
    client.token = token
    log(f"[계정] {email} 로그인 완료")


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


def example_search(client: DocGridClient, query: str, rag_wait: float) -> None:
    log(f"\n== 예시 검색 ==\n질문: {query}")
    status, payload = client.call_json("POST", "/search", {"queryText": query, "topK": 3})
    if status != 200:
        log(f"[검색] 실패(HTTP {status}): {error_text(payload)}")
        return
    data = payload.get("data") or {}
    results = data.get("results") or []
    if not results:
        log("[검색] 결과 없음 — 인덱싱이 끝난 문서가 질문과 관련이 없거나 아직 검색할 수 없습니다.")
    for item in results:
        snippet = " ".join((item.get("chunkText") or "").split())[:SNIPPET_LENGTH]
        log(f"  {item.get('rank')}. {item.get('documentTitle')} "
            f"(유사도 {float(item.get('similarityScore') or 0) * 100:.1f}%) {snippet}…")

    started = time.monotonic()
    while data.get("ragStatus") == "PROCESSING" and time.monotonic() - started < rag_wait:
        time.sleep(2)
        status, payload = client.call("GET", f"/search/{data.get('queryId')}")
        if status != 200:
            log(f"[RAG] 답변 상태 조회 실패(HTTP {status}): {error_text(payload)}")
            return
        data = payload.get("data") or {}
    rag_status = data.get("ragStatus")
    answer = " ".join((data.get("answer") or "").split())
    waited = elapsed_text(time.monotonic() - started)
    if rag_status == "SUCCESS":
        log(f"[RAG] 답변 생성 완료({waited} 대기): {answer[:ANSWER_PREVIEW_LENGTH]}")
    elif rag_status == "FAILED":
        log(f"[RAG] LLM 답변 생성 실패 → 대체 답변 표시({waited} 대기): {answer[:ANSWER_PREVIEW_LENGTH]}")
        log("  → Ollama와 qwen2.5:7b가 준비됐는지 `./scripts/local-services.sh status`로 확인하세요. "
            "검색 결과는 Ollama 없이도 제공됩니다.")
    else:
        log(f"[RAG] {elapsed_text(rag_wait)} 안에 답변이 완성되지 않았습니다. 웹 화면에서 이어서 확인하세요.")


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("DOCGRID_BASE_URL", "http://localhost:8080"),
                        help="Backend 주소 (환경변수 DOCGRID_BASE_URL, 기본 http://localhost:8080)")
    parser.add_argument("--email", default=os.environ.get("DOCGRID_DEMO_EMAIL", "demo@docgrid.local"),
                        help="업로드할 계정 이메일 (환경변수 DOCGRID_DEMO_EMAIL, 기본 demo@docgrid.local)")
    parser.add_argument("--name", default="DocGrid 데모", help="계정이 없을 때 회원가입에 쓸 이름")
    parser.add_argument("--department-id", type=int, default=1,
                        help="계정이 없을 때 회원가입에 쓸 부서 ID (기본 1: 개발팀)")
    parser.add_argument("--corpus-dir", type=Path, default=DEFAULT_CORPUS_DIR, help="예제 문서 디렉터리")
    parser.add_argument("--timeout", type=float,
                        default=float(os.environ.get("DOCGRID_SEED_TIMEOUT", "600")),
                        help="INDEXED 대기 상한(초, 환경변수 DOCGRID_SEED_TIMEOUT, 기본 600)")
    parser.add_argument("--poll-interval", type=float, default=3, help="인덱싱 상태 확인 주기(초)")
    parser.add_argument("--embedding-url", default=os.environ.get("EMBEDDING_SERVER_URL", "http://localhost:8000"),
                        help="업로드 전 준비 확인과 진단에 쓸 임베딩 서버 주소 (환경변수 EMBEDDING_SERVER_URL)")
    parser.add_argument("--skip-embedding-check", action="store_true",
                        help="업로드 전 임베딩 서버 준비 확인을 건너뜀")
    parser.add_argument("--query", default=DEFAULT_QUERY, help="마지막에 실행할 예시 검색 질문")
    parser.add_argument("--rag-wait", type=float, default=60, help="예시 검색의 RAG 답변 대기 상한(초)")
    return parser.parse_args(argv)


def read_password(email: str) -> str:
    password = os.environ.get("DOCGRID_DEMO_PASSWORD")
    if password:
        return password
    if not sys.stdin.isatty():
        raise SeedError("비밀번호가 없습니다. DOCGRID_DEMO_PASSWORD 환경변수로 전달하세요.")
    return getpass.getpass(f"{email} 비밀번호(12자 이상, 계정이 없으면 이 비밀번호로 가입): ")


def run(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    documents = load_manifest(args.corpus_dir)
    client = DocGridClient(args.base_url)
    started = time.monotonic()
    log(f"[시작] Backend {client.base_url} · 예제 문서 {len(documents)}건 · 계정 {args.email}")
    if not args.skip_embedding_check and not embedding_ready(args.embedding_url):
        # 준비 전에 올리면 Job이 자동 재시도 3회를 다 쓰고 실패하므로 업로드 전에 멈춘다.
        raise SeedError(
            f"임베딩 서버({args.embedding_url})가 아직 준비되지 않아 업로드하지 않았습니다.\n"
            f"  → `curl -f {args.embedding_url.rstrip('/')}/health/ready`가 성공한 뒤 다시 실행하세요. "
            "BGE-M3 첫 실행은 모델 다운로드로 오래 걸릴 수 있습니다(`docker compose logs -f embedding-server`).\n"
            "  → Backend가 다른 임베딩 주소를 쓰면 --embedding-url로 지정하거나 --skip-embedding-check를 붙이세요."
        )
    authenticate(client, args.email, read_password(args.email), args.name, args.department_id)

    uploaded: dict[int, str] = {}
    already: set[int] = set()
    upload_failures = 0
    for index, document in enumerate(documents, start=1):
        prefix = f"[업로드] {index:2}/{len(documents)} {document['file']}"
        try:
            doc_id = upload(client, args.corpus_dir, document)["documentId"]
        except SeedError as error:
            upload_failures += 1
            log(f"{prefix} 실패: {error}")
            continue
        uploaded[doc_id] = document["title"]
        # 방금 만든 문서는 Worker가 처리하기 전이므로, 응답 직후 INDEXED면 이전 실행에서 올린 문서다.
        status, payload = client.call("GET", f"/api/documents/{doc_id}/status")
        if status == 200 and classify(payload.get("data") or {}) == "INDEXED":
            already.add(doc_id)
            log(f"{prefix} → 문서 {doc_id} (이전 실행에서 올린 문서, 이미 INDEXED)")
        else:
            log(f"{prefix} → 문서 {doc_id}")
    upload_seconds = time.monotonic() - started

    states, ever_started = wait_for_indexing(client, uploaded, args.timeout, args.poll_interval,
                                             args.embedding_url) if uploaded else ({}, set())
    indexed = [doc_id for doc_id, state in states.items() if state == "INDEXED"]
    failed = [doc_id for doc_id, state in states.items() if state == "FAILED"]
    deleted = [doc_id for doc_id, state in states.items() if state == "DELETED"]
    unfinished = [doc_id for doc_id, state in states.items() if state in ("PENDING", "PROCESSING")]
    total_seconds = time.monotonic() - started

    log("\n== 요약 ==")
    log(f"업로드: {len(uploaded)}/{len(documents)}건 성공 (실패 {upload_failures}건, "
        f"이미 INDEXED였던 기존 문서 {len(already)}건)")
    log(f"INDEXED: {len(indexed)}건 · 인덱싱 실패: {len(failed) + len(deleted)}건 · "
        f"시간 안에 끝나지 않음: {len(unfinished)}건")
    log(f"소요 시간: 업로드 {elapsed_text(upload_seconds)} · 전체 {elapsed_text(total_seconds)}")
    for doc_id in failed:
        log(f"  - 실패 문서 {doc_id} {uploaded[doc_id]}")
    for doc_id in deleted:
        log(f"  - 문서 {doc_id} {uploaded[doc_id]}: 이 계정에서 삭제된 문서입니다. "
            "같은 파일은 다시 만들지 않으므로 다른 --email로 실행하세요.")
    hints = diagnose(states, ever_started, embedding_ready(args.embedding_url), args.embedding_url)
    if hints:
        log("원인 후보:")
        for hint in hints:
            log("  - " + hint)
    if failed:
        log("실패 사유 확인과 재처리는 ADMIN 계정의 웹 대시보드에서 할 수 있습니다. "
            "원인을 해결한 뒤 다른 --email로 다시 실행하면 새 문서로 다시 올립니다.")

    if indexed:
        example_search(client, args.query, args.rag_wait)
        log("\n웹 화면(기본 http://localhost:3000)에서 같은 계정이나 다른 계정으로 로그인해 검색할 수 "
            "있습니다. 예제 문서는 PUBLIC으로 올라가 모든 로그인 사용자가 검색할 수 있습니다.")
    return 0 if len(indexed) == len(documents) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except SeedError as error:
        log(f"[실패] {error}")
        raise SystemExit(2) from None
    except KeyboardInterrupt:
        log("\n[중단] 다시 실행해도 이미 올린 문서는 중복 생성되지 않습니다.")
        raise SystemExit(130) from None
