#!/usr/bin/env bash
# Run one GCP-internal k6 experiment with a live, allowlisted per-request log.
set -euo pipefail
umask 077
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

run_id="${1:?run ID required}"
rate="${2:?requests per second required}"
duration="${3:?duration such as 60s required}"
target="${4:?internal probe URL required}"
token_file="${5:?token file required}"
purpose_code="${6:-baseline-write}"
# Keep prior runs at 40 VUs; an explicit seventh argument isolates preallocation in comparison runs.
initial_vus="${7:-40}"
case "$purpose_code" in
  baseline-write) purpose='HA probe 정상 쓰기 기준선' ;;
  proxy-a-fault) purpose='OpenProxy A 지속 장애 중 쓰기' ;;
  proxy-b-fault) purpose='OpenProxy B 지속 장애 중 쓰기' ;;
  primary-switchover) purpose='OpenSQL 계획 역할 이전 중 쓰기' ;;
  primary-process-fault) purpose='OpenSQL primary PostgreSQL 종료 중 쓰기' ;;
  primary-vm-fault) purpose='OpenSQL primary VM 상실 중 쓰기' ;;
  *) echo '시험 목적 코드가 잘못되었습니다' >&2; exit 2 ;;
esac
# A leader handoff can hold many requests until the 10 s client timeout.
# Raising this budget may reduce drops; each run still checks dropped_iterations.
max_vus=160
if [[ "$purpose_code" == primary-switchover ||
      "$purpose_code" == primary-process-fault ||
      "$purpose_code" == primary-vm-fault ]]; then max_vus=800; fi
if [[ ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$ ]] ||
   [[ ! "$rate" =~ ^[0-9]+$ ]] || (( rate < 1 || rate > 500 )) ||
   [[ ! "$duration" =~ ^[0-9]+s$ ]] ||
   [[ ! "$target" =~ ^http://[0-9.]+/api/ha-probe/writes$ ]] ||
   [[ ! "$initial_vus" =~ ^[0-9]+$ ]] || (( initial_vus < 1 || initial_vus > max_vus )) ||
   [[ ! -f "$token_file" ]] || [[ "$(stat -c '%a' "$token_file")" != 600 ]]; then
  echo '실행 입력 또는 토큰 파일 권한이 잘못되었습니다' >&2
  exit 2
fi
if [[ ! -r "$script_dir/ha_load_telemetry.py" ]]; then
  echo 'VM 계측기가 배치되지 않았습니다' >&2
  exit 2
fi

run_dir="/home/giminkim/ha389-runs/$run_id"
mkdir -m 0700 -p /home/giminkim/ha389-runs
mkdir -m 0700 "$run_dir"
printf '실행 ID=%s\n위치=GCP 내부 부하 VM\n목적=%s\n시작 UTC=%s\n요청 속도=%s req/s\n계획 시간=%s\n성공 기준=HTTP 201, dropped_iterations 0, DB 대조 누락·중복 0\n' \
  "$run_id" "$purpose" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$rate" "$duration" > "$run_dir/실행-기록.txt"

export HA_RUN_ID="$run_id" HA_RATE="$rate" HA_DURATION="$duration"
export HA_TARGET_URL="$target" HA_JWT="$(< "$token_file")"
export HA_SUMMARY_FILE="$run_dir/k6-summary.json"
export HA_VUS="$initial_vus" HA_MAX_VUS="$max_vus" HA_TIMEOUT=10s
printf '초기 VU=%s\n설정 최대 VU=%s\n계측 간격=1초\n지표 태그=모두 비활성\n' \
  "$HA_VUS" "$HA_MAX_VUS" >> "$run_dir/실행-기록.txt"
# 1. Stream tag-free k6 points and numeric /proc samples into this run alone.
cleanup_children() {
  if [[ -n "${sampler_pid:-}" ]]; then kill "$sampler_pid" 2>/dev/null || true; fi
  if [[ -n "${k6_pid:-}" ]]; then kill "$k6_pid" 2>/dev/null || true; fi
}
trap cleanup_children EXIT
set +e
k6 run --quiet --log-format=raw --console-output="$run_dir/k6-events.jsonl" \
  --out "json=$run_dir/k6-metrics.jsonl" \
  "$script_dir/ha_probe_load.js" >/dev/null 2>/dev/null &
k6_pid=$!
env -u HA_JWT -u HA_TARGET_URL python3 "$script_dir/ha_load_telemetry.py" sample --pid "$k6_pid" \
  --out "$run_dir/host-samples.csv" >/dev/null 2>/dev/null &
sampler_pid=$!
wait "$k6_pid"
exit_code=$?
k6_pid=''
kill "$sampler_pid" 2>/dev/null || true
wait "$sampler_pid"
sampler_exit=$?
sampler_pid=''
set -e
unset HA_JWT
# 2. Reject a missing sample or unequal per-second and whole-run counts.
telemetry_exit=0
python3 "$script_dir/ha_load_telemetry.py" summarize \
  --metrics "$run_dir/k6-metrics.jsonl" --host "$run_dir/host-samples.csv" \
  --summary "$run_dir/k6-summary.json" --out "$run_dir/계측-1초.csv" \
  > "$run_dir/계측-요약.json" 2>/dev/null || telemetry_exit=$?
printf '종료 UTC=%s\nk6 종료 코드=%s\nVM 표본기 종료 코드=%s\n1초 계측 검증 종료 코드=%s\n실시간 안전 이벤트=%s건\n원시 k6 stderr=비밀·내부 주소 사전 제거 보장 불가로 수집하지 않음\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$exit_code" "$sampler_exit" "$telemetry_exit" \
  "$(wc -l < "$run_dir/k6-events.jsonl")" >> "$run_dir/실행-기록.txt"
printf 'run_id=%s k6_exit=%s events=%s\n' "$run_id" "$exit_code" \
  "$(wc -l < "$run_dir/k6-events.jsonl")"
if (( sampler_exit != 0 || telemetry_exit != 0 )); then exit 98; fi
exit "$exit_code"
