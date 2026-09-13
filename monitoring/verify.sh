#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
PROMETHEUS_IMAGE=prom/prometheus:v3.5.5
ALERTMANAGER_IMAGE=quay.io/prometheus/alertmanager:v0.33.1
SECRET_DIR=$(mktemp -d "${TMPDIR:-/tmp}/docgrid-alertmanager-secrets.XXXXXX")

cleanup() {
  rm -rf "$SECRET_DIR"
}
trap cleanup EXIT INT TERM

# 1. 예시 설정은 실제 값 대신 형식이 유효한 임시 Secret 파일로만 검증한다.
printf '%s\n' 'https://discord.com/api/webhooks/123456789/test-token' > "$SECRET_DIR/discord-webhook-url"
printf '%s\n' 'https://hooks.slack.com/services/test/test/test' > "$SECRET_DIR/slack-webhook-url"
printf '%s\n' 'https://example.webhook.office.com/webhookb2/test' > "$SECRET_DIR/microsoft-teams-webhook-url"
printf '%s\n' '123456789:test-token' > "$SECRET_DIR/telegram-bot-token"
printf '%s\n' '123456789' > "$SECRET_DIR/telegram-chat-id"
printf '%s\n' 'test-smtp-password' > "$SECRET_DIR/smtp-password"
printf '%s\n' 'http://127.0.0.1:18084/alerts' > "$SECRET_DIR/webhook-url"
chmod 600 "$SECRET_DIR"/*

# 2. Prometheus 본체·규칙·모든 rule test를 고정 버전 promtool로 검사한다.
docker run --rm --entrypoint=promtool \
  -v "$ROOT_DIR/monitoring/prometheus:/etc/prometheus:ro" \
  "$PROMETHEUS_IMAGE" check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint=promtool \
  -v "$ROOT_DIR/monitoring/prometheus:/etc/prometheus:ro" \
  "$PROMETHEUS_IMAGE" check rules \
  /etc/prometheus/rules/embedding-provider-alerts.yml \
  /etc/prometheus/rules/docgrid-backend-alerts.yml \
  /etc/prometheus/rules/docgrid-pipeline-alerts.yml
docker run --rm --entrypoint=promtool \
  -v "$ROOT_DIR/monitoring/prometheus:/etc/prometheus:ro" \
  "$PROMETHEUS_IMAGE" test rules \
  /etc/prometheus/tests/docgrid-backend-alerts.test.yml \
  /etc/prometheus/tests/docgrid-pipeline-alerts.test.yml \
  /etc/prometheus/tests/docgrid-operational-alerts.test.yml
docker run --rm --entrypoint=promtool \
  -v "$ROOT_DIR/monitoring/alertmanager/tests:/test:ro" \
  "$PROMETHEUS_IMAGE" check rules /test/rules-firing.yml /test/rules-resolved.yml

# 3. 기본 무전송 설정과 채널별 완전한 예시를 같은 Alertmanager 버전으로 파싱한다.
docker run --rm --entrypoint=amtool \
  -v "$ROOT_DIR/monitoring/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  "$ALERTMANAGER_IMAGE" check-config /etc/alertmanager/alertmanager.yml
docker run --rm --entrypoint=amtool \
  -v "$ROOT_DIR/monitoring/alertmanager/tests/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  "$ALERTMANAGER_IMAGE" check-config /etc/alertmanager/alertmanager.yml
for config_file in "$ROOT_DIR"/monitoring/alertmanager/examples/*.yml; do
  docker run --rm --entrypoint=amtool \
    -v "$config_file:/etc/alertmanager/alertmanager.yml:ro" \
    -v "$SECRET_DIR:/etc/alertmanager/secrets:ro" \
    "$ALERTMANAGER_IMAGE" check-config /etc/alertmanager/alertmanager.yml
done

# 4. 사용자가 실행할 두 Compose 형태가 모두 정상 렌더링되는지 확인한다.
docker compose -f "$ROOT_DIR/docker-compose.yml" config --quiet
docker compose -f "$ROOT_DIR/docker-compose.yml" --profile monitoring config --quiet

if [ "${1:-}" = "--e2e" ]; then
  "$ROOT_DIR/monitoring/alertmanager/tests/run-e2e.sh"
fi

echo "Monitoring configuration validation: SUCCESS"
