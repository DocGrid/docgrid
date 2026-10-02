#!/usr/bin/env bash
# Run one GCP-internal k6 WebSocket measurement; A/B fixture logs own session counts.
set -euo pipefail

run_id=${1:?run ID required}
clients=${2:?subscriber count required}
output_root=${3:?output directory required}
token_file=${4:?subscriber token file required}

if [[ ! $run_id =~ ^[a-zA-Z0-9_-]{4,64}$ ]] || [[ ! $clients =~ ^(1|5|20|50)$ ]]; then
  printf 'invalid_run_argument\n' >&2
  exit 2
fi
if [[ -z ${DOCGRID_LB_VIP:-} ]]; then
  printf 'missing_required_endpoint\n' >&2
  exit 2
fi
if [[ ! -r $token_file || $(stat -c %a "$token_file") != 600 ]]; then
  printf 'missing_or_insecure_test_token\n' >&2
  exit 2
fi

umask 077
mkdir -m 700 "$output_root"
printf 'run_id=%s\n목적=내부_LB_WebSocket_분산\n구독자수=%s\n시작_KST=%s\n' \
  "$run_id" "$clients" "$(TZ=Asia/Seoul date '+%Y-%m-%dT%H:%M:%S%z')" \
  > "$output_root/status.txt"

# 1. k6 emits raw points into a FIFO; the Python capture stores allowlisted numbers only.
# Each app fixture independently records authenticated STOMP session counts in its TSV.
set +e
python3 k6_dashboard_capture.py \
  --run-id "$run_id" --variant ab --clients "$clients" \
  --warmup-seconds 10 --measure-seconds 30 \
  --url "ws://${DOCGRID_LB_VIP}/ws/websocket" \
  --token-file "$token_file" --script k6_dashboard_load.js \
  --output-dir "$output_root/k6"
k6_code=$?
set -e

# 2. k6 success is not an A/B distribution verdict; reconcile both app TSVs separately.
printf '종료_KST=%s\nk6_종료코드=%s\n' \
  "$(TZ=Asia/Seoul date '+%Y-%m-%dT%H:%M:%S%z')" "$k6_code" \
  >> "$output_root/status.txt"
printf 'run_id=%s k6_exit=%s distribution_requires_A_B_fixture_logs=yes\n' \
  "$run_id" "$k6_code"
if [[ $k6_code -ne 0 ]]; then
  exit 1
fi
