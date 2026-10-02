#!/usr/bin/env python3
"""Exercise real GCP A/B apps, OpenSQL, and GCS with one synthetic PDF.

This is a functional baseline, not an HA or throughput test. Tokens, account
details, document IDs, internal addresses, and response bodies stay in memory;
only stage outcomes, elapsed time, byte count, and a content hash are logged.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import secrets
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from zoneinfo import ZoneInfo


KST = ZoneInfo("Asia/Seoul")


class ProbeFailure(Exception):
    """Carry only a safe stage and failure category, never an HTTP body."""


def log(stage: str, result: str, **numbers: object) -> None:
    timestamp = datetime.now(KST).isoformat(timespec="milliseconds")
    measurements = " ".join(f"{key}={value}" for key, value in numbers.items())
    print(f"{timestamp} 단계={stage} 결과={result} {measurements}".rstrip(), flush=True)


def request(stage: str, url: str, method: str = "GET", body: bytes | None = None,
            headers: dict[str, str] | None = None, expected: int = 200) -> bytes:
    started = time.monotonic()
    outgoing = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(outgoing, timeout=15) as response:
            status = response.status
            content = response.read()
    except urllib.error.HTTPError as error:
        raise ProbeFailure(f"{stage}:HTTP_{error.code}") from None
    except (urllib.error.URLError, TimeoutError):
        raise ProbeFailure(f"{stage}:NETWORK") from None
    if status != expected:
        raise ProbeFailure(f"{stage}:HTTP_{status}")
    log(stage, "통과", http_status=status,
        elapsed_ms=round((time.monotonic() - started) * 1000, 1))
    return content


def json_request(stage: str, url: str, payload: dict[str, object], expected: int) -> dict:
    content = request(stage, url, "POST", json.dumps(payload).encode(),
                      {"Content-Type": "application/json"}, expected)
    try:
        return json.loads(content)
    except json.JSONDecodeError:
        raise ProbeFailure(f"{stage}:INVALID_JSON") from None


def synthetic_pdf(marker: str) -> bytes:
    """Build a one-page text PDF with correct byte offsets and a unique marker."""
    text = f"DocGrid GCP baseline {marker}".encode("ascii")
    stream = b"BT /F1 12 Tf 72 720 Td (" + text + b") Tj ET"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
    ]
    result = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{number} 0 obj\n".encode() + obj + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode())
    result.extend(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(result)


def multipart_pdf(pdf: bytes, filename: str) -> tuple[bytes, str]:
    boundary = "docgrid-" + secrets.token_hex(12)
    parts = []
    for name, value in (("title", "GCP synthetic PDF baseline"),
                        ("description", "Synthetic integration test"),
                        ("visibility", "PRIVATE")):
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n".encode())
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: application/pdf\r\n\r\n".encode() + pdf + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def run() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--upload-via", choices=("A", "B", "LB"), required=True)
    parser.add_argument("--base-a", required=True)
    parser.add_argument("--base-b", required=True)
    parser.add_argument("--base-lb", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]{5,80}", args.run_id):
        raise ProbeFailure("run_id:INVALID")
    bases = {"A": args.base_a.rstrip("/"), "B": args.base_b.rstrip("/"),
             "LB": args.base_lb.rstrip("/")}
    marker = args.run_id + "-" + secrets.token_hex(4)
    pdf = synthetic_pdf(marker)
    digest = hashlib.sha256(pdf).hexdigest()
    log("시작", "실행", run_id=args.run_id, upload_via=args.upload_via,
        pdf_bytes=len(pdf), pdf_sha256=digest)

    # 1. Create a disposable identity through the LB; never log its password or JWT.
    account = "baseline-" + secrets.token_hex(8) + "@example.invalid"
    password = secrets.token_urlsafe(24)
    json_request("회원가입-LB", bases["LB"] + "/auth/signup",
                 {"email": account, "password": password,
                  "name": "Synthetic Baseline", "departmentId": 1}, 201)
    login = json_request("로그인-LB", bases["LB"] + "/auth/login",
                         {"email": account, "password": password}, 200)
    token = login.get("data", {}).get("accessToken")
    if not isinstance(token, str) or not token:
        raise ProbeFailure("로그인-LB:NO_TOKEN")
    authorization = {"Authorization": "Bearer " + token}

    # 2. The same JWT must work on both backend JVMs against the shared DB/Redis path.
    for label in ("A", "B"):
        request("인증공유-" + label, bases[label] + "/auth/me", headers=authorization)

    # 3. Upload through the selected app and download independently from A, B, and LB.
    body, content_type = multipart_pdf(pdf, marker + ".pdf")
    uploaded = request("PDF업로드-" + args.upload_via,
                       bases[args.upload_via] + "/api/documents", "POST", body,
                       authorization | {"Content-Type": content_type}, 201)
    try:
        document_id = json.loads(uploaded)["data"]["documentId"]
    except (ValueError, KeyError, TypeError):
        raise ProbeFailure("PDF업로드:INVALID_RESPONSE") from None
    if not isinstance(document_id, int) or document_id <= 0:
        raise ProbeFailure("PDF업로드:INVALID_DOCUMENT_ID")
    for label in ("A", "B", "LB"):
        downloaded = request("PDF다운로드-" + label,
                             bases[label] + f"/api/documents/{document_id}/file",
                             headers=authorization)
        if hashlib.sha256(downloaded).hexdigest() != digest:
            raise ProbeFailure("PDF다운로드-" + label + ":HASH_MISMATCH")
        log("해시대조-" + label, "일치", bytes=len(downloaded))

    log("종료", "통과", uploads=1, downloads=3, hash_matches=3,
        pdf_bytes=len(pdf), pdf_sha256=digest, synthetic_data="유지")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except ProbeFailure as error:
        # Only the fixed stage/category is printed; URL, body, user, and token are omitted.
        log("종료", "실패", category=str(error))
        raise SystemExit(1) from None
