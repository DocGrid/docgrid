#!/usr/bin/env python3
"""Check whether a revoked test JWT can subscribe to the admin topic on A/B."""

from __future__ import annotations

import argparse
import asyncio
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path


KST = timezone(timedelta(hours=9))


async def probe(url: str, token: str) -> str:
    """Check SUBSCRIBE, not CONNECT: a valid non-admin may still authenticate."""
    import websockets

    subscribed = False
    try:
        async with websockets.connect(url, open_timeout=10) as socket:
            await socket.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n"
                              f"Authorization:Bearer {token}\n\n\0")
            while True:
                raw = await asyncio.wait_for(socket.recv(), timeout=5)
                for frame in str(raw).split("\0"):
                    frame = frame.lstrip("\n")
                    if frame.startswith("CONNECTED") and not subscribed:
                        await socket.send("SUBSCRIBE\nid:revoked-probe\n"
                                          "destination:/topic/dashboard\nack:auto\n\n\0")
                        subscribed = True
                    if frame.startswith("ERROR"):
                        return "거부_ERROR"
                    if frame.startswith("MESSAGE"):
                        return "관리자_메시지_노출"
    except asyncio.TimeoutError:
        return "시간초과"
    except websockets.exceptions.ConnectionClosed:
        return "거부_연결종료" if subscribed else "연결_조기종료"
    except OSError:
        return "연결실패"


async def probe_both(url_a: str, url_b: str, token: str) -> tuple[str, str]:
    """Run both protocol probes concurrently without sharing their sockets."""
    result_a, result_b = await asyncio.gather(probe(url_a, token), probe(url_b, token))
    return result_a, result_b


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--url-a", required=True)
    parser.add_argument("--url-b", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    token = Path(args.token_file).read_text(encoding="utf-8").strip()
    # 1. Probe each physical backend using the same already-revoked user.
    results = asyncio.run(probe_both(args.url_a, args.url_b, token))
    # 2. Store only the branch label and protocol outcome; no private address or JWT.
    report = {"run_id": args.run_id, "시각_KST": datetime.now(KST).isoformat(),
              "A": results[0], "B": results[1]}
    with Path(args.output).open("x", encoding="utf-8") as output:
        output.write(json.dumps(report, ensure_ascii=False) + "\n")
    print(json.dumps(report, ensure_ascii=False))
    if any(not result.startswith("거부_") for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
