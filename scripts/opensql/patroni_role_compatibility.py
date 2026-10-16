#!/usr/bin/env python3
"""격리 OpenProxy 시험에서 Patroni 동기 standby 역할 이름만 replica로 변환한다.

원본 공급사 바이너리는 수정하지 않는다. 쓰기·캐시·설정 변경을 전달하지 않으며
상류의 scope와 알려진 역할을 매 요청 검증한다. 공급사 공식 지원을 대신하지 않는다.
"""
import argparse
import ipaddress
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from urllib.request import urlopen

MAX_BYTES = 1024 * 1024
ROLE_ALIASES = {"sync_standby": "replica", "quorum_standby": "replica"}
KNOWN_ROLES = {"leader", "primary", "replica"} | set(ROLE_ALIASES)


def read_json(url):
    with urlopen(url, timeout=2) as response:
        raw = response.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("upstream response too large")
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("upstream object required")
    return value


def normalized_role(value):
    if value not in KNOWN_ROLES:
        raise ValueError("unsupported role")
    return ROLE_ALIASES.get(value, value)


class CompatibilityServer(ThreadingHTTPServer):
    """읽기 전용 시험 어댑터의 상류·scope만 보관하며 권한·클러스터 상태는 저장하지 않는다."""
    daemon_threads = True

    def __init__(self, address, upstream, scope):
        self.upstream = upstream.rstrip("/")
        self.scope = scope
        super().__init__(address, CompatibilityHandler)


class CompatibilityHandler(BaseHTTPRequestHandler):
    """fresh scope 검증 후 역할 별칭만 변환하고 실패는 내용 없이 502로 닫는다."""
    def log_message(self, *_args):
        # 주소·상류 payload·오류의 원시 값을 로그로 보존하지 않는다.
        pass

    def do_GET(self):
        if self.path not in ("/cluster", "/patroni"):
            self.send_empty(404)
            return
        try:
            # 1. 다른 클러스터나 재사용된 포트의 상태를 역할 근거로 삼지 않는다.
            identity = read_json(self.server.upstream + "/patroni")
            if identity.get("patroni", {}).get("scope") != self.server.scope:
                raise ValueError("scope mismatch")
            # 2. 캐시 없이 실제 상류 응답을 읽고 standby 별칭만 변경한다.
            if self.path == "/cluster":
                value = read_json(self.server.upstream + "/cluster")
                members = value.get("members")
                if not isinstance(members, list) or not members or len(members) > 128:
                    raise ValueError("invalid members")
                for member in members:
                    member["role"] = normalized_role(member.get("role"))
            else:
                value = identity
                value["role"] = normalized_role(value.get("role"))
            raw = json.dumps(value).encode()
        except Exception:
            self.send_empty(502)
            return
        # 3. Primary·주소·복제 수·기타 원장 값은 그대로 전달한다. 내용은 로깅하지 않는다.
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def send_empty(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def reject_mutation(self):
        self.send_empty(405)

    do_POST = reject_mutation
    do_PATCH = reject_mutation
    do_DELETE = reject_mutation
    do_PUT = reject_mutation
    do_HEAD = reject_mutation


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18010)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--scope", required=True)
    args = parser.parse_args()
    address = ipaddress.ip_address(args.listen)
    target = urlsplit(args.upstream)
    if not isinstance(address, ipaddress.IPv4Address) or address.is_unspecified or not address.is_private:
        parser.error("listen must be a specified private IPv4 address")
    if target.scheme != "http" or target.username or target.password or target.path not in ("", "/") or target.query or target.fragment:
        parser.error("plain private upstream origin required")
    host = ipaddress.ip_address(target.hostname)
    if host.is_unspecified or not host.is_private or not target.port:
        parser.error("private upstream with explicit port required")
    server = CompatibilityServer((args.listen, args.port), args.upstream, args.scope)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
