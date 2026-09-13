#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/docgrid-alertmanager-e2e.XXXXXX")
COMPOSE_PROJECT_NAME="docgrid-alertmanager-e2e-$$"
export ALERTING_E2E_TMP_DIR="$TMP_DIR"

cleanup() {
  docker compose -p "$COMPOSE_PROJECT_NAME" -f "$SCRIPT_DIR/docker-compose.yml" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT INT TERM

mkdir -p "$TMP_DIR/results"
cp "$SCRIPT_DIR/rules-firing.yml" "$TMP_DIR/active-rules.yml"
touch "$TMP_DIR/results/events.jsonl"

# 1. 전용 Stack만 실행해 개발 DB와 실제 수신 채널에 영향을 주지 않는다.
docker compose -p "$COMPOSE_PROJECT_NAME" -f "$SCRIPT_DIR/docker-compose.yml" up -d --wait

PROMETHEUS_ADDRESS=$(docker compose -p "$COMPOSE_PROJECT_NAME" -f "$SCRIPT_DIR/docker-compose.yml" port prometheus 9090)
PROMETHEUS_URL="http://$PROMETHEUS_ADDRESS"

# 2. Prometheus가 Alertmanager를 active 대상으로 발견했는지 API 응답으로 확인한다.
DISCOVERY_FILE="$TMP_DIR/alertmanagers.json"
discovery_attempts=0
until curl --fail --silent --show-error "$PROMETHEUS_URL/api/v1/alertmanagers" > "$DISCOVERY_FILE" \
  && python3 -c 'import json,sys; data=json.load(open(sys.argv[1])); assert data["status"] == "success" and len(data["data"]["activeAlertmanagers"]) == 1' "$DISCOVERY_FILE" >/dev/null 2>&1; do
  discovery_attempts=$((discovery_attempts + 1))
  if [ "$discovery_attempts" -ge 20 ]; then
    echo "Prometheus가 Alertmanager를 20초 안에 발견하지 못했습니다." >&2
    cat "$DISCOVERY_FILE" >&2
    docker compose -p "$COMPOSE_PROJECT_NAME" -f "$SCRIPT_DIR/docker-compose.yml" logs --no-color >&2
    exit 1
  fi
  sleep 1
done

wait_for_event() {
  mode=$1
  attempts=0
  until python3 "$SCRIPT_DIR/assert_events.py" "$TMP_DIR/results/events.jsonl" "$mode" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [ "$attempts" -ge 45 ]; then
      echo "Alertmanager $mode webhook를 45초 안에 확인하지 못했습니다." >&2
      cat "$TMP_DIR/results/events.jsonl" >&2
      docker compose -p "$COMPOSE_PROJECT_NAME" -f "$SCRIPT_DIR/docker-compose.yml" logs --no-color >&2
      return 1
    fi
    sleep 1
  done
}

# 3. 같은 label 집합의 두 instance가 한 webhook으로 묶이고 파생 경보가 억제되는지 확인한다.
firing_started_at=$(date +%s)
wait_for_event firing
firing_finished_at=$(date +%s)
firing_elapsed=$((firing_finished_at - firing_started_at))

# 4. 동일 파일을 제자리에서 바꾸고 Prometheus를 reload해 resolved 전달까지 검증한다.
resolved_started_at=$(date +%s)
cat "$SCRIPT_DIR/rules-resolved.yml" > "$TMP_DIR/active-rules.yml"
curl --fail --silent --show-error -X POST "$PROMETHEUS_URL/-/reload" >/dev/null
wait_for_event resolved
resolved_finished_at=$(date +%s)
resolved_elapsed=$((resolved_finished_at - resolved_started_at))

echo "Alertmanager routing E2E: SUCCESS (firing ${firing_elapsed}s, resolved ${resolved_elapsed}s)"
