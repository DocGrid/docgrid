#!/usr/bin/env python3
"""Write a short-lived synthetic fixture JWT without printing or logging its secret."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import time
import uuid
from pathlib import Path


def encoded(value):
    """Encode one compact-JWT segment without exposing its contents to stdout."""
    return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ttl-seconds", type=int, default=1200)
    args = parser.parse_args()
    if args.user_id < 1 or not re.fullmatch(r"ha-[a-z0-9-]+-admin@invalid[.]example", args.subject) or not 60 <= args.ttl_seconds <= 1800:
        parser.error("합성 ADMIN subject·사용자 ID·60~1800초 TTL만 허용합니다")
    values = {}
    for line in args.env_file.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            if key == "JWT_SECRET":
                values[key] = value
    secret = values.get("JWT_SECRET", "").encode()
    if len(secret) < 32:
        parser.error("JWT_SECRET이 없거나 너무 짧습니다")
    algorithm, digest = (("HS512", hashlib.sha512) if len(secret) >= 64 else
                         ("HS384", hashlib.sha384) if len(secret) >= 48 else
                         ("HS256", hashlib.sha256))
    now = int(time.time())
    header = encoded({"alg": algorithm, "typ": "JWT"})
    payload = encoded({"sub": args.subject, "userId": args.user_id,
                       "jti": str(uuid.uuid4()), "iat": now, "exp": now + args.ttl_seconds})
    signed = header + b"." + payload
    signature = base64.urlsafe_b64encode(hmac.new(secret, signed, digest).digest()).rstrip(b"=")
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(signed + b"." + signature)
        output.flush()
        os.fsync(output.fileno())
    print("합성 ADMIN JWT 생성 완료; 값은 출력하지 않음")


if __name__ == "__main__":
    main()
