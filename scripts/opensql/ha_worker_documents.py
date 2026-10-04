#!/usr/bin/env python3
"""Upload distinct synthetic PDF and DOCX files for one real Worker HA run.

Only run ID, document ordinal, type, byte count, hash, and HTTP stage are logged.
Credentials, JWTs, internal URLs, and database identifiers stay in memory.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import secrets
import sys
import time
import zipfile
from xml.sax.saxutils import escape

from verify_gcp_app_gcs_baseline import ProbeFailure, json_request, log, request, synthetic_pdf


def synthetic_docx(marker: str) -> bytes:
    """Create a valid minimal OOXML document with marker-specific searchable text."""
    paragraphs = "".join(
        f"<w:p><w:r><w:t>{escape(f'DocGrid Worker recovery {marker} passage {index:03d}.')}</w:t></w:r></w:p>"
        for index in range(1, 31)
    )
    body = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        f"<w:body>{paragraphs}<w:sectPr/></w:body></w:document>"
    )
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "[Content_Types].xml",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
            '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
            '<Default Extension="xml" ContentType="application/xml"/>'
            '<Override PartName="/word/document.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
            '</Types>',
        )
        archive.writestr(
            "_rels/.rels",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            '<Relationship Id="rId1" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
            'Target="word/document.xml"/></Relationships>',
        )
        archive.writestr("word/document.xml", body)
    return output.getvalue()


def multipart_document(content: bytes, title: str, kind: str) -> tuple[bytes, str]:
    """Make one upload body without persisting document contents or credentials."""
    boundary = "docgrid-" + secrets.token_hex(12)
    content_type = (
        "application/pdf" if kind == "pdf" else
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    )
    fields = (("title", title), ("description", "Synthetic Worker recovery test"),
              ("visibility", "PRIVATE"))
    parts = [
        f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
        for name, value in fields
    ]
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="{title}.{kind}"\r\nContent-Type: {content_type}\r\n\r\n'.encode()
        + content + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def document_sequence(pdf_count: int, docx_count: int) -> list[tuple[str, int]]:
    """Interleave both formats before a mid-upload fault can interrupt one entire kind."""
    return [
        (kind, ordinal)
        for ordinal in range(1, max(pdf_count, docx_count) + 1)
        for kind, count in (("pdf", pdf_count), ("docx", docx_count))
        if ordinal <= count
    ]


def run() -> int:
    """Create an isolated account and upload a bounded, mixed-format batch."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--base-lb", required=True)
    parser.add_argument("--pdf-count", type=int, default=8)
    parser.add_argument("--docx-count", type=int, default=4)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]{5,40}", args.run_id):
        raise ProbeFailure("run_id:INVALID")
    if not 1 <= args.pdf_count <= 30 or not 1 <= args.docx_count <= 30:
        raise ProbeFailure("document_count:INVALID")
    base = args.base_lb.rstrip("/")
    log("시작", "실행", run_id=args.run_id, pdf_count=args.pdf_count,
        docx_count=args.docx_count)

    # 1. The account and JWT live only in this process; no secret file is created.
    account = "worker-" + secrets.token_hex(8) + "@example.invalid"
    password = secrets.token_urlsafe(24)
    json_request("회원가입", base + "/auth/signup",
                 {"email": account, "password": password,
                  "name": "Synthetic Worker HA", "departmentId": 1}, 201)
    login = json_request("로그인", base + "/auth/login",
                         {"email": account, "password": password}, 200)
    token = login.get("data", {}).get("accessToken")
    if not isinstance(token, str) or not token:
        raise ProbeFailure("로그인:NO_TOKEN")
    authorization = {"Authorization": "Bearer " + token}

    # 2. Alternate formats so a fault during upload still leaves both kinds in the accepted cohort.
    for kind, ordinal in document_sequence(args.pdf_count, args.docx_count):
        title = f"{args.run_id}-{kind}-{ordinal:02d}"
        content = synthetic_pdf(title) if kind == "pdf" else synthetic_docx(title)
        body, multipart_type = multipart_document(content, title, kind)
        started = time.monotonic()
        response = request(f"문서업로드-{kind}-{ordinal:02d}",
                           base + "/api/documents", "POST", body,
                           authorization | {"Content-Type": multipart_type}, 201)
        try:
            document_id = json.loads(response)["data"]["documentId"]
        except (ValueError, TypeError, KeyError):
            raise ProbeFailure("문서업로드:INVALID_RESPONSE") from None
        if not isinstance(document_id, int) or document_id <= 0:
            raise ProbeFailure("문서업로드:INVALID_DOCUMENT_ID")
        log("문서접수", "통과", run_id=args.run_id, kind=kind, ordinal=ordinal,
            bytes=len(content), sha256=hashlib.sha256(content).hexdigest(),
            elapsed_ms=round((time.monotonic() - started) * 1000, 1))
    log("종료", "접수완료", total=args.pdf_count + args.docx_count)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(run())
    except ProbeFailure as error:
        log("종료", "실패", category=str(error))
        raise SystemExit(1) from None
