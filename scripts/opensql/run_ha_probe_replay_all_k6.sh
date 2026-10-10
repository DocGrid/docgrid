#!/usr/bin/env bash
# Replay the frozen synthetic IDs at a fixed k6 arrival rate without exposing JWT or URL logs.
set -euo pipefail
umask 077
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

replay_dir="${1:?prepared replay directory required}"
target="${2:?internal idempotent probe URL required}"
token_file="${3:?private JWT file required}"
rate="${4:-30}"
vus="${5:-160}"
manifest="$replay_dir/재전송-전체-목록.json"
summary="$replay_dir/k6-replay-summary.json"
journal="$replay_dir/재전송-시도.jsonl"
metrics="$replay_dir/k6-replay-metrics.jsonl"
record="$replay_dir/재전송-실행-기록.txt"

if [[ ! "$rate" =~ ^[0-9]+$ ]] || (( rate < 1 || rate > 100 )) ||
   [[ ! "$vus" =~ ^[0-9]+$ ]] || (( vus < 1 || vus > 800 )) ||
   [[ ! -d "$replay_dir" || ! -f "$manifest" || ! -f "$replay_dir/재전송-전-대조.json" ]] ||
   [[ -e "$summary" || -e "$journal" || -e "$metrics" || -e "$record" ]] ||
   [[ ! -f "$token_file" ]] || [[ "$(stat -c '%a' "$token_file")" != 600 ]] ||
   [[ "$(stat -c '%a' "$replay_dir")" != 700 ]]; then
  echo 'K6_REPLAY_INVALID_INPUT_OR_EXISTING_EVIDENCE' >&2
  exit 2
fi

# 1. Validate the destination and private token before k6 can open either one.
source_run_id="$(PYTHONPATH="$script_dir" python3 - "$target" "$token_file" "$manifest" "$rate" 2>/dev/null <<'PY'
import json
import math
import sys
from pathlib import Path
from retry_idempotent_ha_probe import read_token, validate_target, validate_token_lifetime

validate_target(sys.argv[1])
token = read_token(Path(sys.argv[2]))
manifest = json.loads(Path(sys.argv[3]).read_text(encoding="utf-8"))
if manifest.get("schema_version") != 1 or not manifest.get("request_ids"):
    raise ValueError("invalid manifest")
validate_token_lifetime(token, math.ceil(len(manifest["request_ids"]) / int(sys.argv[4])) + 90)
print(manifest["run_id"])
PY
)" || { echo 'K6_REPLAY_DESTINATION_OR_TOKEN_REJECTED' >&2; exit 2; }
if [[ ! "$source_run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$ ]]; then
  echo 'K6_REPLAY_INVALID_SOURCE_RUN_ID' >&2
  exit 2
fi

export HA_SOURCE_RUN_ID="$source_run_id" HA_TARGET_URL="$target"
export HA_REPLAY_MANIFEST="$manifest" HA_REPLAY_RATE="$rate"
export HA_REPLAY_VUS="$vus" HA_REPLAY_MAX_VUS=800 HA_SUMMARY_FILE="$summary"
export HA_JWT="$(< "$token_file")"
printf '원본 실행 ID=%s\n시작 UTC=%s\n재전송 범위=원본 원장의 전체 전송 ID\n도착률=%s req/s\n초기 VU=%s\n내부 대상 URL·JWT=기록하지 않음\n' \
  "$source_run_id" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$rate" "$vus" > "$record"
printf 'k6 전체 동일 ID 재전송 시작 | 실행 ID=%s | 속도=%s req/s | 초기 VU=%s\n' \
  "$source_run_id" "$rate" "$vus"

# 2. Only the allowlisted k6 console events go into the private, exclusive run directory.
cleanup_k6() {
  if [[ -n "${k6_pid:-}" ]]; then kill "$k6_pid" 2>/dev/null || true; fi
}
trap cleanup_k6 EXIT
set +e
k6 run --quiet --log-format=raw --console-output="$journal" \
  --out "json=$metrics" "$script_dir/ha_probe_replay_all.js" >/dev/null 2>/dev/null &
k6_pid=$!
# Print only aggregate counts; raw events and any unredactable k6 diagnostics stay off screen.
while kill -0 "$k6_pid" 2>/dev/null; do
  sleep 5
  python3 - "$journal" <<'PY'
import json
import sys
from pathlib import Path

counts = {"200": 0, "201": 0, "other": 0, "unknown": 0}
path = Path(sys.argv[1])
if path.exists():
    with path.open(encoding="utf-8") as source:
        for line in source:
            try:
                event = json.loads(line)
            except json.JSONDecodeError:
                continue
            if event.get("kind") != "result":
                continue
            status = event.get("http_status")
            bucket = str(status) if status in (200, 201) else "unknown" if status is None else "other"
            counts[bucket] += 1
print("재전송 진행 | 완료={total} | 200={ok200} | 201={ok201} | 기타={other} | 불명={unknown}".format(
    total=sum(counts.values()), ok200=counts["200"], ok201=counts["201"],
    other=counts["other"], unknown=counts["unknown"]))
PY
done
wait "$k6_pid"
k6_exit=$?
k6_pid=''
set -e
trap - EXIT
unset HA_JWT
printf '종료 UTC=%s\nk6 종료 코드=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$k6_exit" >> "$record"

# 3. Keep the original and replay outcomes separate; the DB-after check runs later.
if [[ ! -f "$summary" || ! -f "$journal" ]]; then
  echo 'K6_REPLAY_MISSING_SUMMARY_OR_JOURNAL' >&2
  exit 98
fi
if ! python3 - "$summary" "$k6_exit" 2>/dev/null <<'PY'
import json
import sys
from pathlib import Path

summary = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
counts = summary["status_counts"]
print("k6 재전송 종료 | 원장 ID={manifest_requests} | HTTP={http_requests} | "
      "200={ok200} | 201={ok201} | 기타={other} | 불명={unknown} | 미전송={dropped} | k6 종료={exit}".format(
          manifest_requests=summary["manifest_requests"], http_requests=summary["http_requests"],
          ok200=counts["200"], ok201=counts["201"], other=counts["other"],
          unknown=counts["unknown"], dropped=summary["dropped_iterations"], exit=sys.argv[2]))
PY
then
  echo 'K6_REPLAY_INVALID_SUMMARY' >&2
  exit 98
fi
exit "$k6_exit"
