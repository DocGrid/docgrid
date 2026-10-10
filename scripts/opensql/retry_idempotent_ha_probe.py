#!/usr/bin/env python3
"""Replay only failed/unknown synthetic HA writes after preserving pre-retry DB evidence.

The command never retries an original 201 or a 4xx. It records every retry attempt without
URLs, tokens, exception text, or response bodies; final DB state is checked separately.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import ipaddress
import json
import os
import random
import re
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from ha_evidence import replay, write_private
from sanitize_ha_k6_events import RUN_ID, safe_event


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Treat redirects as HTTP failures so a JWT is never sent to another location."""

    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def read_initial(events_path: Path, summary_path: Path, run_id: str) -> dict:
    """Rebuild one first-attempt outcome per ID from the allowlisted k6 stream."""
    if not RUN_ID.fullmatch(run_id):
        raise ValueError("실행 ID가 올바르지 않습니다")
    with events_path.open(encoding="utf-8") as source:
        events = [safe_event(json.loads(line), run_id) for line in source]
    if not events:
        raise ValueError("첫 시도 원장이 비어 있습니다")
    finished = {"event_id": "retry-read-finished", "run_id": run_id,
                "at": events[-1]["at"], "kind": "finished"}
    _, rows = replay({"run_id": run_id, "started_at": events[0]["at"]}, events + [finished])
    if not rows or any(row["operation"] != "ha_probe_idempotent_write" for row in rows):
        raise ValueError("멱등 HA probe 원장만 재전송할 수 있습니다")
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if (summary.get("run_id") != run_id or summary.get("iterations") != len(rows)
            or summary.get("http_requests") != len(rows)
            or not isinstance(summary.get("dropped_iterations"), int)):
        raise ValueError("k6 요약과 첫 시도 원장이 일치하지 않습니다")
    return {row["request_id"]: row for row in rows}


def read_db_counts(path: Path, run_id: str, request_ids: set[str]) -> dict[str, int]:
    """Reject foreign IDs and unexpected duplicates in the unique-key probe table."""
    counts = {}
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames != ["request_id", "row_count"]:
            raise ValueError("DB CSV는 request_id,row_count 열이어야 합니다")
        for row in reader:
            request_id = row["request_id"]
            count_text = row["row_count"]
            if (request_id not in request_ids or not request_id.startswith(run_id + "-v")
                    or request_id in counts or not re.fullmatch(r"[1-9][0-9]*", count_text)):
                raise ValueError("DB CSV에 타 실행 ID, 중복 또는 잘못된 행 수가 있습니다")
            counts[request_id] = int(count_text)
    return counts


def prepare(events_path: Path, summary_path: Path, db_before: Path, run_id: str) -> tuple[dict, dict]:
    """Calculate the irreversible pre-retry comparison before opening any HTTP connection."""
    rows = read_initial(events_path, summary_path, run_id)
    before = read_db_counts(db_before, run_id, set(rows))
    acknowledged = [row for row in rows.values() if row["outcome"] == "ACKNOWLEDGED"]
    failed = [row for row in rows.values() if row["outcome"] == "FAILED"]
    unknown = [row for row in rows.values() if row["outcome"] == "UNKNOWN"]
    candidates = [row for row in rows.values() if row["outcome"] == "UNKNOWN" or
                  row["outcome"] == "FAILED" and isinstance(row["http_status"], int)
                  and 500 <= row["http_status"] < 600]
    unexpected_2xx = [row for row in failed if isinstance(row["http_status"], int)
                      and 200 <= row["http_status"] < 300]
    if unexpected_2xx:
        raise ValueError("첫 시도에 예상 밖 2xx가 있어 재전송을 중단합니다")
    pre_result = {
        "run_id": run_id,
        "observed_at": utc_now(),
        "events_sha256": hashlib.sha256(events_path.read_bytes()).hexdigest(),
        "db_before_sha256": hashlib.sha256(db_before.read_bytes()).hexdigest(),
        "initial_requests": len(rows),
        "initial_201": len(acknowledged),
        "initial_failed": len(failed),
        "initial_unknown": len(unknown),
        "initial_201_missing_before_retry": sum(row["request_id"] not in before for row in acknowledged),
        "db_duplicate_ids_before_retry": sum(count > 1 for count in before.values()),
        "failed_db_present_before_retry": sum(row["request_id"] in before for row in failed),
        "unknown_db_present_before_retry": sum(row["request_id"] in before for row in unknown),
        "retry_candidates": len(candidates),
        "not_retried_4xx_or_3xx": len(failed) - sum(row in candidates for row in failed),
        "k6_dropped_iterations": json.loads(summary_path.read_text(encoding="utf-8"))["dropped_iterations"],
    }
    return rows, pre_result


def validate_target(target: str) -> None:
    parsed = urllib.parse.urlsplit(target)
    if (parsed.scheme != "http" or parsed.path != "/api/ha-probe/idempotent-writes"
            or parsed.port not in (None, 80) or parsed.query or parsed.fragment
            or parsed.username or parsed.password):
        raise ValueError("멱등 HA probe 내부 URL만 허용합니다")
    try:
        address = ipaddress.ip_address(parsed.hostname or "")
    except ValueError as error:
        raise ValueError("대상은 내부 IPv4 주소여야 합니다") from error
    if address.version != 4 or not (address.is_private or address.is_loopback):
        raise ValueError("대상은 내부 IPv4 주소여야 합니다")


def read_token(path: Path) -> str:
    details = path.stat()
    if not stat.S_ISREG(details.st_mode) or stat.S_IMODE(details.st_mode) != 0o600:
        raise ValueError("JWT 파일은 일반 파일이며 권한 0600이어야 합니다")
    token = path.read_text(encoding="utf-8").strip()
    if not token or "\n" in token:
        raise ValueError("JWT 파일 형식이 올바르지 않습니다")
    return token


def send(target: str, token: str, run_id: str, request_id: str) -> int | None:
    """Return only an HTTP code; transport failures remain unknown, never assumed absent."""
    body = json.dumps({"runId": run_id, "requestId": request_id, "payload": request_id},
                      separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(target, data=body, headers={
        "Authorization": "Bearer " + token,
        "Content-Type": "application/json",
        "X-Ha-Run-Id": run_id,
        "X-Ha-Request-Id": request_id,
    }, method="POST")
    # Disable environment proxies and redirect following for this private JWT-bearing request.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirectHandler())
    try:
        with opener.open(request, timeout=10) as response:
            return response.status
    except urllib.error.HTTPError as error:
        error.close()
        return error.code
    except (urllib.error.URLError, TimeoutError, OSError):
        return None


def append_event(output, event: dict) -> None:
    output.write(json.dumps(event, ensure_ascii=True, sort_keys=True) + "\n")
    output.flush()
    os.fsync(output.fileno())


def run_retries(args, *, sender=send, sleeper=time.sleep, progress=None) -> dict:
    """Persist the pre-retry snapshot, then make bounded, paced attempts per candidate."""
    # 1. Validate the initial evidence, target and token before creating a retry run directory.
    rows, pre_result = prepare(args.events, args.summary, args.db_before, args.run_id)
    validate_target(args.target)
    token = read_token(args.token_file)
    if args.max_retries < 1 or args.max_retries > 5 or args.max_rate < 1 or args.max_rate > 100:
        raise ValueError("재전송 횟수 또는 초당 속도가 허용 범위를 벗어났습니다")
    args.output_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    write_private(args.output_dir / "재전송-전-대조.json",
                  json.dumps(pre_result, ensure_ascii=False, indent=2) + "\n", exclusive=True)
    if pre_result["db_duplicate_ids_before_retry"]:
        raise ValueError("재전송 전에 DB 중복이 발견돼 중단합니다")
    # 2. Each synthetic ID keeps the original payload; sent/result pairs are fsynced immediately.
    journal_fd = os.open(args.output_dir / "재전송-시도.jsonl", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    last_started = 0.0
    resolved = set()
    attempts = 0
    stop_status = None
    with os.fdopen(journal_fd, "w", encoding="utf-8") as journal:
        candidates = sorted((row for row in rows.values() if row["outcome"] == "UNKNOWN" or
                             row["outcome"] == "FAILED" and isinstance(row["http_status"], int)
                             and 500 <= row["http_status"] < 600), key=lambda row: row["sent_at"])
        for candidate_number, row in enumerate(candidates, 1):
            for retry_number in range(1, args.max_retries + 1):
                if retry_number > 1:
                    sleeper(min(4.0, 0.5 * 2 ** (retry_number - 2)) * random.uniform(0.8, 1.2))
                remaining = 1.0 / args.max_rate - (time.monotonic() - last_started)
                if remaining > 0:
                    sleeper(remaining)
                last_started = time.monotonic()
                request_id = row["request_id"]
                append_event(journal, {"run_id": args.run_id, "request_id": request_id,
                                       "attempt": retry_number + 1, "kind": "sent", "at": utc_now()})
                status = sender(args.target, token, args.run_id, request_id)
                attempts += 1
                append_event(journal, {"run_id": args.run_id, "request_id": request_id,
                                       "attempt": retry_number + 1, "kind": "result", "at": utc_now(),
                                       "http_status": status})
                if progress is not None:
                    progress(f"재전송 {candidate_number}/{len(candidates)} | 추가 시도 {retry_number}"
                             f" | HTTP {status if status is not None else '결과 불명'}")
                if status in (200, 201):
                    resolved.add(request_id)
                    break
                if status is not None and not 500 <= status < 600:
                    # A fresh 3xx/4xx indicates a wrong route, expired JWT, or contract conflict.
                    stop_status = status
                    break
            if stop_status is not None:
                break
    # 3. This is an HTTP-only result; the independent post-retry DB export settles final state.
    result = {"run_id": args.run_id, "finished_at": utc_now(), "retry_candidates": len(candidates),
              "retry_attempts": attempts, "http_resolved": len(resolved),
              "http_unresolved": len(candidates) - len(resolved),
              "stopped_on_non_retryable_http": stop_status, "db_after_verified": False}
    write_private(args.output_dir / "재전송-HTTP-요약.json",
                  json.dumps(result, ensure_ascii=False, indent=2) + "\n", exclusive=True)
    return result


def verify_after(args) -> dict:
    """Compare first replies, replay replies, and the final primary DB without merging them."""
    rows, pre_result = prepare(args.events, args.summary, args.db_before, args.run_id)
    saved_pre = json.loads((args.replay_dir / "재전송-전-대조.json").read_text(encoding="utf-8"))
    for key in ("run_id", "events_sha256", "db_before_sha256", "initial_201_missing_before_retry"):
        if saved_pre.get(key) != pre_result[key]:
            raise ValueError("재전송 전 증거가 실행 이후 변경되었습니다")
    before = read_db_counts(args.db_before, args.run_id, set(rows))
    after = read_db_counts(args.db_after, args.run_id, set(rows))
    terminal = {}
    outstanding = set()
    with (args.replay_dir / "재전송-시도.jsonl").open(encoding="utf-8") as source:
        for line in source:
            event = json.loads(line)
            request_id = event.get("request_id")
            key = (request_id, event.get("attempt"))
            if event.get("run_id") != args.run_id or request_id not in rows or \
               event.get("kind") not in {"sent", "result"} or not isinstance(event.get("attempt"), int):
                raise ValueError("재전송 원장에 다른 실행 또는 잘못된 시도가 섞였습니다")
            if event["kind"] == "sent":
                if key in outstanding or key in terminal:
                    raise ValueError("재전송 시도 ID가 중복되었습니다")
                outstanding.add(key)
            else:
                if key not in outstanding or event.get("http_status") is not None and \
                   not isinstance(event.get("http_status"), int):
                    raise ValueError("재전송 결과가 해당 시도와 맞지 않습니다")
                outstanding.remove(key)
                terminal[key] = event["http_status"]
    candidates = {request_id for request_id, row in rows.items() if row["outcome"] == "UNKNOWN"
                  or row["outcome"] == "FAILED" and isinstance(row["http_status"], int)
                  and 500 <= row["http_status"] < 600}
    if any(key[0] not in candidates for key in set(terminal) | outstanding):
        raise ValueError("재전송 대상이 아닌 ID를 다시 보냈습니다")
    last_status = {request_id: status for (request_id, _), status in terminal.items()}
    resolved = {request_id for request_id, status in last_status.items() if status in (200, 201)}
    result = {
        "run_id": args.run_id,
        "observed_at": utc_now(),
        "initial_201_missing_before_retry": pre_result["initial_201_missing_before_retry"],
        "initial_201_missing_after_retry": sum(request_id not in after for request_id, row in rows.items()
                                               if row["outcome"] == "ACKNOWLEDGED"),
        "retry_candidates": len(candidates),
        "retry_http_resolved": len(resolved),
        "retry_http_unresolved": len(candidates - resolved),
        "retry_200_already_present_before": sum(last_status.get(request_id) == 200 and request_id in before
                                                 for request_id in candidates),
        "retry_200_not_present_before": sum(last_status.get(request_id) == 200 and request_id not in before
                                             for request_id in candidates),
        "retry_201_new": sum(last_status.get(request_id) == 201 for request_id in candidates),
        "retry_conflict_409": sum(last_status.get(request_id) == 409 for request_id in candidates),
        "resolved_missing_from_final_db": sum(request_id not in after for request_id in resolved),
        "final_db_rows": sum(after.values()),
        "db_duplicate_ids_after_retry": sum(count > 1 for count in after.values()),
        "sent_attempt_without_result": len(outstanding),
        "db_after_sha256": hashlib.sha256(args.db_after.read_bytes()).hexdigest(),
    }
    result["final_pass"] = (result["initial_201_missing_before_retry"] == 0
                            and result["initial_201_missing_after_retry"] == 0
                            and pre_result["db_duplicate_ids_before_retry"] == 0
                            and result["db_duplicate_ids_after_retry"] == 0
                            and result["retry_http_unresolved"] == 0
                            and result["retry_conflict_409"] == 0
                            and result["resolved_missing_from_final_db"] == 0
                            and result["sent_attempt_without_result"] == 0
                            and pre_result["k6_dropped_iterations"] == 0)
    write_private(args.replay_dir / "재전송-최종-대조.json",
                  json.dumps(result, ensure_ascii=False, indent=2) + "\n", exclusive=True)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in (commands.add_parser("run"), commands.add_parser("verify")):
        command.add_argument("--run-id", required=True)
        command.add_argument("--events", type=Path, required=True)
        command.add_argument("--summary", type=Path, required=True)
        command.add_argument("--db-before", type=Path, required=True)
    run = commands.choices["run"]
    run.add_argument("--target", required=True)
    run.add_argument("--token-file", type=Path, required=True)
    run.add_argument("--output-dir", type=Path, required=True)
    run.add_argument("--max-retries", type=int, default=3)
    run.add_argument("--max-rate", type=int, default=20)
    verify = commands.choices["verify"]
    verify.add_argument("--db-after", type=Path, required=True)
    verify.add_argument("--replay-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_retries(args, progress=lambda line: print(line, flush=True)) \
            if args.command == "run" else verify_after(args)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        # Never copy an exception message: a URL, token, or private path may be inside it.
        print("HA_RETRY_INVALID_OR_INCOMPLETE_EVIDENCE", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if args.command == "run" or result["final_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
