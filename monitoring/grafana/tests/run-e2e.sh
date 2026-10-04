#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
COMPOSE_FILE="$ROOT_DIR/monitoring/grafana/tests/docker-compose.yml"
RUN_ID=$(date -u +%Y%m%dt%H%M%Sz)-$$
PROJECT_NAME="docgrid-grafana-$RUN_ID"
GRAFANA_TEST_PORT=$(python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)
export GRAFANA_TEST_PORT

cleanup() {
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "[시작] run_id=$RUN_ID grafana_url=http://127.0.0.1:$GRAFANA_TEST_PORT"

# 1. 실제 Prometheus와 Grafana를 격리 Project로 기동한다.
docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" up -d --wait

# 2. Provisioning 결과와 모든 Dashboard PromQL을 실제 HTTP API로 검증한다.
python3 "$ROOT_DIR/monitoring/grafana/tests/assert_dashboard.py" \
  "http://127.0.0.1:$GRAFANA_TEST_PORT"

# 3. 종료 Trap이 Container와 익명 Volume을 회수한다.
echo "[완료] run_id=$RUN_ID result=SUCCESS"
