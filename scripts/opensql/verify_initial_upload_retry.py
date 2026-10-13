#!/usr/bin/env python3
"""Check initial-upload retries across two live DocGrid apps without exposing test credentials."""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import re
import secrets
import socket
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from verify_gcp_app_gcs_baseline import multipart_pdf, synthetic_pdf


KST = ZoneInfo("Asia/Seoul")


class ProbeFailure(Exception):
    """Carry only a safe stage and failure category, never a response body."""


def log(stage: str, result: str, **values: object) -> None:
    detail = " ".join(f"{key}={value}" for key, value in values.items())
    print(f"{datetime.now(KST).isoformat(timespec='milliseconds')} 단계={stage} "
          f"결과={result} {detail}".rstrip(), flush=True)


def request(url: str, method: str = "GET", body: bytes | None = None,
            headers: dict[str, str] | None = None) -> tuple[int, bytes, str | None]:
    outgoing = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(outgoing, timeout=30) as response:
            return response.status, response.read(), response.headers.get("Retry-After")
    except urllib.error.HTTPError as error:
        return error.code, error.read(), error.headers.get("Retry-After")
    except (urllib.error.URLError, TimeoutError):
        raise ProbeFailure("NETWORK") from None


def response_data(stage: str, status: int, raw: bytes, expected: int) -> dict:
    if status != expected:
        raise ProbeFailure(f"{stage}:HTTP_{status}")
    try:
        data = json.loads(raw)["data"]
    except (ValueError, KeyError, TypeError):
        raise ProbeFailure(f"{stage}:INVALID_JSON") from None
    if not isinstance(data, dict):
        raise ProbeFailure(f"{stage}:INVALID_DATA")
    log(stage, "통과", http_status=status)
    return data


def upload(base: str, body: bytes, content_type: str, token: str, key: str,
           stage: str) -> dict:
    headers = {"Authorization": "Bearer " + token, "Content-Type": content_type,
               "Idempotency-Key": key}
    for attempt in range(1, 7):
        started = time.monotonic()
        status, raw, retry_after = request(base + "/api/documents", "POST", body, headers)
        if status == 409:
            try:
                code = json.loads(raw).get("code")
            except (ValueError, TypeError):
                code = None
            if code == "DOCUMENT-UPLOAD-004" and attempt < 6:
                log(stage, "진행중", http_status=409, attempt=attempt,
                    retry_after_s=retry_after if retry_after in ("5",) else "미확인")
                time.sleep(5)
                continue
        data = response_data(stage, status, raw, 201)
        log(stage + "-시간", "관측", attempt=attempt,
            elapsed_ms=round((time.monotonic() - started) * 1000, 1))
        return data
    raise ProbeFailure(stage + ":RETRIES_EXHAUSTED")


def wait_for_gate(path: str, stage: str) -> None:
    """Wait for an operator-confirmed fault or recovery without logging local paths."""
    log(stage, "대기", timeout_s=180)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        if Path(path).is_file():
            log(stage, "확인")
            return
        time.sleep(0.2)
    raise ProbeFailure(stage + ":TIMEOUT")


def send_without_response(base: str, body: bytes, content_type: str,
                          token: str, key: str) -> None:
    """Transmit a complete local-tunnel request, then close before reading HTTP status."""
    target = urlsplit(base)
    if target.scheme != "http" or target.hostname != "127.0.0.1" or not target.port:
        raise ProbeFailure("RESPONSE_DROP:UNSAFE_TARGET")
    connection = http.client.HTTPConnection(target.hostname, target.port, timeout=30)
    try:
        connection.request("POST", "/api/documents", body=body,
                           headers={"Authorization": "Bearer " + token,
                                    "Content-Type": content_type,
                                    "Idempotency-Key": key})
        # No getresponse(): the client cannot know whether the server committed.
        connection.sock.shutdown(socket.SHUT_RDWR)
    except OSError:
        raise ProbeFailure("RESPONSE_DROP:SEND_FAILED") from None
    finally:
        connection.close()
    log("최초응답유실-A", "전송후연결종료", request_bytes=len(body), observed_http_status="없음")


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--base-a", required=True)
    parser.add_argument("--base-b", required=True)
    parser.add_argument("--mode", choices=("normal", "proxy-fault", "primary-process-fault",
                                           "response-drop"),
                        default="normal")
    parser.add_argument("--fault-gate")
    parser.add_argument("--recovery-gate")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]{5,80}", args.run_id):
        raise ProbeFailure("RUN_ID_INVALID")
    if args.mode in ("proxy-fault", "primary-process-fault") and (
        not args.fault_gate or not args.recovery_gate
    ):
        raise ProbeFailure("GATE_ARGUMENTS_MISSING")
    if args.mode == "response-drop" and not args.recovery_gate:
        raise ProbeFailure("REPLAY_GATE_MISSING")
    base_a, base_b = args.base_a.rstrip("/"), args.base_b.rstrip("/")
    marker = args.run_id + "-" + secrets.token_hex(4)
    pdf = synthetic_pdf(marker)
    digest = hashlib.sha256(pdf).hexdigest()
    filename = marker + ".pdf"
    body, content_type = multipart_pdf(pdf, filename)
    key = secrets.token_urlsafe(24)
    log("시작", "실행", run_id=args.run_id, mode=args.mode, revision="e94d539", pdf_bytes=len(pdf),
        pdf_sha256=digest)

    # 1. Use a synthetic identity; account, password, JWT, and request key stay in memory.
    email = "upload-retry-" + secrets.token_hex(8) + "@example.invalid"
    password = secrets.token_urlsafe(24)
    status, raw, _ = request(base_a + "/auth/signup", "POST",
                             json.dumps({"email": email, "password": password,
                                         "name": "Synthetic Retry", "departmentId": 1}).encode(),
                             {"Content-Type": "application/json"})
    if status != 201:
        raise ProbeFailure(f"SIGNUP:HTTP_{status}")
    log("회원가입-A", "통과", http_status=status)
    status, raw, _ = request(base_a + "/auth/login", "POST",
                             json.dumps({"email": email, "password": password}).encode(),
                             {"Content-Type": "application/json"})
    token = response_data("로그인-A", status, raw, 200).get("accessToken")
    if not isinstance(token, str) or not token:
        raise ProbeFailure("LOGIN:NO_TOKEN")

    # 2. In fault mode the operator confirms the target outage before the first upload.
    if args.mode == "response-drop":
        send_without_response(base_a, body, content_type, token, key)
        first = None
        wait_for_gate(args.recovery_gate, "재전송전DB확인")
    elif args.mode != "normal":
        wait_for_gate(args.fault_gate, "장애확인")
        status, raw, _ = request(base_a + "/api/documents", "POST", body,
                                 {"Authorization": "Bearer " + token,
                                  "Content-Type": content_type, "Idempotency-Key": key})
        log("장애중최초업로드-A", "관측", http_status=status)
        if status == 201:
            first = response_data("장애중최초업로드-A-결과", status, raw, 201)
        elif status == 409:
            try:
                code = json.loads(raw).get("code")
            except (ValueError, TypeError):
                code = None
            if code not in ("DOCUMENT-UPLOAD-004", "DOCUMENT-UPLOAD-005"):
                raise ProbeFailure("FAULT_FIRST:UNEXPECTED_CONFLICT")
            first = None
        elif status in (500, 503):
            first = None
        else:
            raise ProbeFailure(f"FAULT_FIRST:HTTP_{status}")
        wait_for_gate(args.recovery_gate, "복구확인")
    else:
        first = upload(base_a, body, content_type, token, key, "최초업로드-A")

    # 3. Replay identical bytes and key through B after the first request or proxy recovery.
    second = upload(base_b, body, content_type, token, key, "동일키재시도-B")
    fields = ("documentId", "documentVersionId", "fileObjectId", "embeddingJobId")
    if any(second.get(field) is None for field in fields):
        raise ProbeFailure("REPLAY:MISSING_RESULT")
    if first is not None and any(first.get(field) != second.get(field) for field in fields):
        raise ProbeFailure("REPLAY:RESULT_MISMATCH")
    log("결과대조", "일치" if first is not None else "최초응답불명",
        compared_ids=len(fields) if first is not None else 0)

    # 4. The same key with different file bytes must fail instead of creating a second document.
    different, different_type = multipart_pdf(synthetic_pdf(marker + "-different"), filename)
    status, raw, _ = request(base_b + "/api/documents", "POST", different,
                             {"Authorization": "Bearer " + token,
                              "Content-Type": different_type, "Idempotency-Key": key})
    try:
        error_code = json.loads(raw).get("code")
    except (ValueError, TypeError):
        error_code = None
    if status != 409 or error_code != "DOCUMENT-UPLOAD-003":
        raise ProbeFailure(f"CONFLICT:HTTP_{status}")
    log("다른입력충돌", "통과", http_status=status)

    # 5. Both JVMs must return the original GCS bytes through the committed document.
    for label, base in (("A", base_a), ("B", base_b)):
        status, data, _ = request(base + f"/api/documents/{second['documentId']}/file",
                                  headers={"Authorization": "Bearer " + token})
        if status != 200 or hashlib.sha256(data).hexdigest() != digest:
            raise ProbeFailure(f"DOWNLOAD_{label}:HASH_OR_STATUS")
        log("원본다운로드-" + label, "통과", http_status=status, bytes=len(data), hash_matches=1)

    log("종료", "통과", upload_attempts=2, successful_replays=1, conflicts=1,
        downloads=2, hash_matches=2, synthetic_data="유지")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except ProbeFailure as error:
        log("종료", "실패", category=str(error))
        raise SystemExit(1) from None
