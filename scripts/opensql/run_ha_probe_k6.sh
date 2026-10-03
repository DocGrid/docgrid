#!/usr/bin/env bash
# Run one GCP-internal k6 experiment with a live, allowlisted per-request log.
set -euo pipefail
umask 077

run_id="${1:?run ID required}"
rate="${2:?requests per second required}"
duration="${3:?duration such as 60s required}"
target="${4:?internal probe URL required}"
token_file="${5:?token file required}"
purpose_code="${6:-baseline-write}"
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
if [[ "$purpose_code" == primary-switchover ]]; then max_vus=800; fi
if [[ ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$ ]] ||
   [[ ! "$rate" =~ ^[0-9]+$ ]] || (( rate < 1 || rate > 500 )) ||
   [[ ! "$duration" =~ ^[0-9]+s$ ]] ||
   [[ ! "$target" =~ ^http://[0-9.]+/api/ha-probe/writes$ ]] ||
   [[ ! -f "$token_file" ]] || [[ "$(stat -c '%a' "$token_file")" != 600 ]]; then
  echo '실행 입력 또는 토큰 파일 권한이 잘못되었습니다' >&2
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
export HA_VUS=40 HA_MAX_VUS="$max_vus" HA_TIMEOUT=10s
set +e
k6 run --quiet --log-format=raw --console-output="$run_dir/k6-events.jsonl" \
  /home/giminkim/ha_probe_load_389.js >/dev/null 2>/dev/null
exit_code=$?
set -e
unset HA_JWT
printf '종료 UTC=%s\nk6 종료 코드=%s\n실시간 안전 이벤트=%s건\n원시 k6 stderr=비밀·내부 주소 사전 제거 보장 불가로 수집하지 않음\n' \
  "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$exit_code" \
  "$(wc -l < "$run_dir/k6-events.jsonl")" >> "$run_dir/실행-기록.txt"
printf 'run_id=%s k6_exit=%s events=%s\n' "$run_id" "$exit_code" \
  "$(wc -l < "$run_dir/k6-events.jsonl")"
exit "$exit_code"
