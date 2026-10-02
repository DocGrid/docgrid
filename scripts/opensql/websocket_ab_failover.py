#!/usr/bin/env python3
"""Measure one existing A WebSocket loss and a new internal-LB connection to B."""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import time


KST = timezone(timedelta(hours=9))


def timestamp() -> str:
    """Return a wall-clock label while elapsed time uses a monotonic clock."""
    return datetime.now(KST).isoformat()


async def await_message(socket, token: str, seconds: float) -> None:
    """Authenticate, subscribe, and require a real dashboard MESSAGE frame."""
    await socket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n"
                      f"Authorization:Bearer {token}\n\n\0")
    deadline = time.monotonic() + seconds
    connected = False
    while time.monotonic() < deadline:
        raw = await asyncio.wait_for(socket.recv(), timeout=max(0.1, deadline - time.monotonic()))
        for frame in str(raw).split("\0"):
            frame = frame.lstrip("\n")
            if frame.startswith("ERROR"):
                raise RuntimeError("STOMP_ERROR")
            if frame.startswith("CONNECTED") and not connected:
                await socket.send("SUBSCRIBE\nid:failover-probe\n"
                                  "destination:/topic/dashboard\nack:auto\n\n\0")
                connected = True
            if frame.startswith("MESSAGE") and connected:
                return
    raise TimeoutError("DASHBOARD_MESSAGE_TIMEOUT")


async def run(args: argparse.Namespace) -> dict:
    import websockets

    token = Path(args.token_file).read_text(encoding="utf-8").strip()
    ready = Path(args.ready_file)
    with Path(args.output).open("x", encoding="utf-8") as events:
        def record(event: str, **numbers: float | int | str) -> None:
            events.write(json.dumps({"run_id": args.run_id, "시각_KST": timestamp(),
                                     "이벤트": event, **numbers}, ensure_ascii=False) + "\n")
            events.flush()

        # 1. Pin an existing authenticated subscription to A before the controlled shutdown.
        async with websockets.connect(args.url_a, open_timeout=10) as original:
            await await_message(original, token, 15)
            record("A_기존_구독_메시지_확인")
            ready.touch(exist_ok=False)
            # 2. The test operator stops A only after the ready marker is visible.
            try:
                await asyncio.wait_for(original.wait_closed(), timeout=90)
            except asyncio.TimeoutError:
                record("A_기존_연결_미종료", 제한초=90)
                return {"run_id": args.run_id, "결과": "기존_연결_미종료"}
        lost_at = time.monotonic()
        record("A_기존_연결_종료")

        # 3. Existing sockets do not migrate; a new client connection retries through the LB.
        attempts = 0
        while time.monotonic() - lost_at < 90:
            attempts += 1
            try:
                async with websockets.connect(args.url_lb, open_timeout=5) as replacement:
                    await await_message(replacement, token, 8)
                    elapsed_ms = round((time.monotonic() - lost_at) * 1000, 2)
                    record("LB_새_구독_메시지_확인", 시도=attempts, 복구시간_ms=elapsed_ms)
                    return {"run_id": args.run_id, "결과": "새_연결_복구", "시도": attempts,
                            "기존_연결_종료부터_복구_ms": elapsed_ms}
            except Exception as error:
                # Never store an endpoint, header, token, or exception message.
                record("LB_재연결_실패", 시도=attempts, 오류종류=type(error).__name__)
                await asyncio.sleep(1)
        record("LB_재연결_시간초과", 시도=attempts)
        return {"run_id": args.run_id, "결과": "재연결_시간초과", "시도": attempts}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--url-a", required=True)
    parser.add_argument("--url-lb", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--ready-file", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = asyncio.run(run(args))
    print(json.dumps(result, ensure_ascii=False))
    if result["결과"] != "새_연결_복구":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
