#!/usr/bin/env python3
"""Observe actual ADMIN revocation and dashboard STOMP delivery inside GCP."""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

KST = timezone(timedelta(hours=9))


def safe_time(epoch_millis: float) -> str:
    """Convert a numeric event clock to a Korean-readable ISO timestamp."""
    return datetime.fromtimestamp(epoch_millis / 1000, KST).isoformat()


def stomp_frames(raw: str | bytes) -> list[str]:
    """Split the STOMP frames that may be coalesced in one WebSocket message."""
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    return [frame.lstrip("\n") for frame in raw.split("\0") if frame.strip()]


def classify(sent_ms: int, received_ms: float, http_200_ms: float | None) -> str:
    """Use only a certainly post-response publisher start as a new decision candidate."""
    if http_200_ms is None:
        return "회수_전"
    if sent_ms > http_200_ms + 1:
        return "200_이후_새_판정_후보"
    if received_ms > http_200_ms and sent_ms < http_200_ms - 1:
        return "200_전_발행_늦은_수신"
    return "경계_시각_불명확"


def backend_for(index: int, clients: int, clients_b: int) -> str:
    """Assign a deterministic subset to B without recording either private endpoint."""
    return "B" if index >= clients - clients_b else "A"


def revoke(http_url: str, subscriber_id: str, operator_token: str) -> tuple[int, float]:
    """Issue the real admin API call without logging its URL, ID, token, or body."""
    request = Request(
        f"{http_url}/admin/users/{subscriber_id}/roles/ADMIN",
        method="DELETE",
        headers={"Authorization": f"Bearer {operator_token}"},
    )
    try:
        with urlopen(request, timeout=15) as response:
            response.read()
            return response.status, time.time() * 1000
    except HTTPError as error:
        return error.code, time.time() * 1000


async def client(index: int, backend: str, url: str, token: str, ready: asyncio.Event,
                 state: dict, write_event) -> None:
    """Observe one physical subscription without retaining private STOMP headers."""
    import websockets

    try:
        async with websockets.connect(url, open_timeout=10, ping_interval=20) as socket:
            await socket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n"
                              f"Authorization:Bearer {token}\n\n\0")
            while True:
                if any(frame.startswith("CONNECTED") for frame in stomp_frames(
                    await asyncio.wait_for(socket.recv(), timeout=10)
                )):
                    break
            await socket.send(f"SUBSCRIBE\nid:revoke-{index}\n"
                              "destination:/topic/dashboard\nack:auto\n\n\0")
            state["ready"] += 1
            state["ready_by_backend"][backend] += 1
            if state["ready"] == state["clients"]:
                ready.set()
            while not state["stop"].is_set():
                try:
                    raw = await asyncio.wait_for(socket.recv(), timeout=0.5)
                except asyncio.TimeoutError:
                    continue
                received_ms = time.time() * 1000
                for frame in stomp_frames(raw):
                    if frame.startswith("ERROR"):
                        write_event({"이벤트": "STOMP_ERROR", "백엔드": backend, "구독번호": index,
                                     "수신_KST": safe_time(received_ms)})
                        continue
                    if not frame.startswith("MESSAGE\n"):
                        continue
                    try:
                        body = json.loads(frame.partition("\n\n")[2])
                        sequence = int(body["documents"]["total"])
                        sent_ms = int(body["documents"]["searchable"])
                    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
                        write_event({"이벤트": "프레임_형식_오류", "백엔드": backend, "구독번호": index,
                                     "수신_KST": safe_time(received_ms)})
                        continue
                    classification = ("관측_중" if state["observe_only"] else
                                      classify(sent_ms, received_ms, state.get("http_200_ms")))
                    if classification in ("회수_전", "관측_중"):
                        state["pre_messages_by_backend"][backend] += 1
                    write_event({"이벤트": "MESSAGE", "백엔드": backend, "구독번호": index,
                                 "순번": sequence, "발행_KST": safe_time(sent_ms),
                                 "수신_KST": safe_time(received_ms),
                                 "구분": classification})
    except Exception as error:
        # Exception text can include an internal endpoint. Keep only its type.
        write_event({"이벤트": "연결_종료_또는_오류", "백엔드": backend, "구독번호": index,
                     "오류종류": type(error).__name__,
                     "시각_KST": safe_time(time.time() * 1000)})
        if state["ready"] < state["clients"]:
            ready.set()


async def run(args: argparse.Namespace) -> dict:
    subscriber_token = Path(args.subscriber_token_file).read_text().strip()
    operator_token = Path(args.operator_token_file).read_text().strip() if not args.observe_only else None
    subscriber_id = Path(args.subscriber_id_file).read_text().strip() if not args.observe_only else None
    if not args.observe_only and not subscriber_id.isdigit():
        raise ValueError("시험 구독자 ID 파일 형식이 올바르지 않습니다.")
    state = {"ready": 0, "ready_by_backend": {"A": 0, "B": 0},
             "pre_messages_by_backend": {"A": 0, "B": 0},
             "clients": args.clients, "observe_only": args.observe_only,
             "stop": asyncio.Event()}
    counts = {}
    output = Path(args.output)
    with output.open("x", encoding="utf-8") as stream:
        def write_event(event: dict) -> None:
            stream.write(json.dumps({"run_id": args.run_id, **event}, ensure_ascii=False) + "\n")
            stream.flush()
            key = event.get("구분", event["이벤트"])
            counts[key] = counts.get(key, 0) + 1

        ready = asyncio.Event()
        # 1. Direct A/B endpoints make cross-instance authorization measurable without LB routing ambiguity.
        tasks = []
        for index in range(args.clients):
            backend = backend_for(index, args.clients, args.clients_b)
            url = args.websocket_url if backend == "A" else args.websocket_url_b
            tasks.append(asyncio.create_task(client(index, backend, url, subscriber_token,
                                                    ready, state, write_event)))
        try:
            await asyncio.wait_for(ready.wait(), timeout=45)
            if state["ready"] != args.clients:
                raise RuntimeError(f"준비된 구독이 {state['ready']}/{args.clients}개입니다.")
            # 2. Require real delivery on each target before revoking; otherwise zero after revocation is vacuous.
            await asyncio.sleep(args.before_seconds)
            target_backends = ("A", "B") if args.clients_b else ("A",)
            if not args.observe_only and any(
                state["pre_messages_by_backend"][backend] == 0 for backend in target_backends
            ):
                raise RuntimeError("회수 전 백엔드별 메시지 수신이 없어 권한 시험을 중단합니다.")

            if not args.observe_only:
                def revoke_and_record() -> tuple[int, float]:
                    status, boundary = revoke(args.http_url, subscriber_id, operator_token)
                    state["http_200_ms"] = boundary if status == 200 else None
                    return status, boundary

                status, boundary = await asyncio.to_thread(revoke_and_record)
                write_event({"이벤트": "권한_회수_HTTP_응답", "상태코드": status,
                             "응답_KST": safe_time(boundary)})
            # 3. In observation mode, a zero-message B result is evidence, not an early abort.
            await asyncio.sleep(args.after_seconds)
        finally:
            state["stop"].set()
            await asyncio.gather(*tasks, return_exceptions=True)
    message_label = "관측_수신_백엔드별" if args.observe_only else "회수_전_수신_백엔드별"
    return {"run_id": args.run_id, "준비된_구독": state["ready"],
            "준비된_구독_백엔드별": state["ready_by_backend"],
            message_label: state["pre_messages_by_backend"],
            "회수_HTTP_200": state.get("http_200_ms") is not None,
            "이벤트_건수": counts}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--websocket-url", required=True)
    parser.add_argument("--websocket-url-b")
    parser.add_argument("--http-url")
    parser.add_argument("--subscriber-token-file", required=True)
    parser.add_argument("--operator-token-file")
    parser.add_argument("--subscriber-id-file")
    parser.add_argument("--clients", type=int, default=50)
    parser.add_argument("--clients-b", type=int, default=0)
    parser.add_argument("--before-seconds", type=int, default=3)
    parser.add_argument("--after-seconds", type=int, default=8)
    parser.add_argument("--observe-only", action="store_true")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.clients < 1 or args.before_seconds < 1 or args.after_seconds < 1:
        parser.error("구독 수와 전후 관측 시간은 1 이상이어야 합니다.")
    if args.clients_b < 0 or args.clients_b > args.clients or (args.clients_b and not args.websocket_url_b):
        parser.error("B 구독 수는 전체 구독 수 이하이며 B 주소가 함께 필요합니다.")
    if not args.observe_only and not all((args.http_url, args.operator_token_file, args.subscriber_id_file)):
        parser.error("권한 회수 시험에는 관리자 API·토큰·사용자 ID가 필요합니다.")
    result = asyncio.run(run(args))
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
