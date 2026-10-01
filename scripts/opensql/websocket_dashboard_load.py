#!/usr/bin/env python3
"""Measure STOMP dashboard fan-out against an isolated benchmark publisher."""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import time
from dataclasses import dataclass, field
from pathlib import Path

import websockets


def percentile(sorted_values: list[float], fraction: float) -> float | None:
    if not sorted_values:
        return None
    index = max(0, math.ceil(fraction * len(sorted_values)) - 1)
    return round(sorted_values[index], 3)


@dataclass
class ClientResult:
    """Hold one subscriber's connection, delivery, and latency observations."""

    client_id: int
    connected: bool = False
    received: int = 0
    sequences: set[int] = field(default_factory=set)
    latencies_ms: list[float] = field(default_factory=list)
    error: str | None = None


@dataclass
class RunState:
    """Coordinate subscriber readiness and a shared warm-up/measurement window."""

    wanted: int
    ready: int = 0
    window_start: float | None = None
    window_end: float | None = None
    ready_event: asyncio.Event = field(default_factory=asyncio.Event)
    failed_event: asyncio.Event = field(default_factory=asyncio.Event)
    stop_event: asyncio.Event = field(default_factory=asyncio.Event)
    results: list[ClientResult] = field(default_factory=list)


def frames(raw: str | bytes) -> list[str]:
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    return [frame.lstrip("\n") for frame in raw.split("\0") if frame.strip()]


async def one_client(
    client_id: int, url: str, token: str, state: RunState
) -> None:
    result = ClientResult(client_id)
    state.results.append(result)
    try:
        async with websockets.connect(
            url, open_timeout=10, ping_interval=20, max_size=1_000_000
        ) as socket:
            # 1. Authenticate at STOMP CONNECT, then subscribe to the exact guarded topic.
            await socket.send(
                "CONNECT\naccept-version:1.2\nheart-beat:0,0\n"
                f"Authorization:Bearer {token}\n\n\0"
            )
            connected = False
            while not connected:
                for frame in frames(await asyncio.wait_for(socket.recv(), timeout=10)):
                    if frame.startswith("CONNECTED"):
                        connected = True
                    elif frame.startswith("ERROR"):
                        raise RuntimeError("STOMP CONNECT rejected")
            await socket.send(
                f"SUBSCRIBE\nid:bench-{client_id}\n"
                "destination:/topic/dashboard\nack:auto\n\n\0"
            )
            result.connected = True
            state.ready += 1
            if state.ready == state.wanted:
                state.ready_event.set()

            # 2. Discard warm-up frames and record every benchmark frame in the window.
            while not state.stop_event.is_set():
                try:
                    raw = await asyncio.wait_for(socket.recv(), timeout=0.5)
                except asyncio.TimeoutError:
                    continue
                received_at = time.monotonic()
                received_epoch_ms = time.time() * 1000
                for frame in frames(raw):
                    if frame.startswith("ERROR"):
                        raise RuntimeError("STOMP ERROR frame")
                    if not frame.startswith("MESSAGE\n"):
                        continue
                    body = frame.partition("\n\n")[2]
                    try:
                        event = json.loads(body)
                    except json.JSONDecodeError:
                        continue
                    if not isinstance(event, dict) or "sequence" not in event:
                        continue
                    if state.window_start is None or received_at < state.window_start:
                        continue
                    if state.window_end is not None and received_at >= state.window_end:
                        continue
                    result.received += 1
                    result.sequences.add(int(event["sequence"]))
                    result.latencies_ms.append(
                        received_epoch_ms - float(event["sentEpochMillis"])
                    )
    except Exception as error:
        # Keep endpoint and credential-bearing exception text out of result files.
        result.error = type(error).__name__
        if not result.connected:
            state.failed_event.set()


async def run(args: argparse.Namespace) -> dict:
    token = Path(args.token_file).read_text(encoding="utf-8").strip()
    state = RunState(wanted=args.clients)
    tasks = []
    for client_id in range(args.clients):
        tasks.append(asyncio.create_task(one_client(client_id, args.url, token, state)))
        await asyncio.sleep(0.02)

    # 3. Never begin a run with fewer subscriptions than its declared load level.
    ready_wait = asyncio.create_task(state.ready_event.wait())
    failed_wait = asyncio.create_task(state.failed_event.wait())
    await asyncio.wait(
        {ready_wait, failed_wait}, timeout=60, return_when=asyncio.FIRST_COMPLETED
    )
    ready_wait.cancel()
    failed_wait.cancel()
    if state.ready != args.clients:
        state.stop_event.set()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise RuntimeError(f"only {state.ready}/{args.clients} STOMP clients subscribed")

    await asyncio.sleep(args.warmup_seconds)
    state.window_start = time.monotonic()
    await asyncio.sleep(args.duration_seconds)
    state.window_end = time.monotonic()
    state.stop_event.set()
    await asyncio.gather(*tasks, return_exceptions=True)

    all_latencies = sorted(
        latency for result in state.results for latency in result.latencies_ms
    )
    interior_gaps = 0
    for result in state.results:
        if len(result.sequences) >= 2:
            first = min(result.sequences)
            last = max(result.sequences)
            interior_gaps += last - first + 1 - len(result.sequences)
    summary = {
        "clients_requested": args.clients,
        "clients_connected": sum(result.connected for result in state.results),
        "duration_seconds": args.duration_seconds,
        "warmup_seconds": args.warmup_seconds,
        "frames_received": sum(result.received for result in state.results),
        "interior_sequence_gaps": interior_gaps,
        "client_errors": [
            {"client_id": result.client_id, "error": result.error}
            for result in state.results if result.error
        ],
        "latency_ms": {
            "p50": percentile(all_latencies, 0.50),
            "p95": percentile(all_latencies, 0.95),
            "p99": percentile(all_latencies, 0.99),
            "max": round(all_latencies[-1], 3) if all_latencies else None,
            "min": round(all_latencies[0], 3) if all_latencies else None,
        },
    }
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--clients", type=int, required=True)
    parser.add_argument("--duration-seconds", type=int, default=120)
    parser.add_argument("--warmup-seconds", type=int, default=20)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.clients < 1 or args.duration_seconds < 1 or args.warmup_seconds < 0:
        parser.error("clients/duration must be positive and warmup cannot be negative")
    summary = asyncio.run(run(args))
    Path(args.output).write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == "__main__":
    main()
