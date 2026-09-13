# 비동기 Queue 운영 상태 Gauge 검증 결과 (#334)

검증일: 2026-09-13

## Java 대상 테스트

```bash
DB_PORT=55433 \
JWT_SECRET=docgrid-operational-metrics-test-secret-key-2026-with-at-least-32-bytes \
./gradlew test \
  --tests 'com.opensource.docgrid.global.observability.OperationalMetricsTest' \
  --tests 'com.opensource.docgrid.global.observability.OperationalMetricsSnapshotRefresherTest' \
  --tests 'com.opensource.docgrid.global.observability.OperationalMetricsSnapshotQueryServiceTest'
```

결과: **6 tests, 실패 0**

검증 범위:

- Embedding과 Sync Outbox의 미래 Retry·available 시각 제외
- Retry Job의 오래된 `created_at` 대신 실행 가능 시각을 oldest 기준으로 사용
- PROCESSING 수와 Queue가 비었을 때의 0·null 경계
- Worker 상태와 Heartbeat 만료 경계를 함께 적용
- 세 집계 성공 후 Atomic Snapshot 게시
- 집계 실패 시 마지막 정상 Gauge 보존과 실패 Counter 증가
- Gauge 반복 조회 시 QueryService 호출 0회
- 첫 정상 갱신 전 Snapshot 나이 `+Inf`

## Prometheus 규칙 테스트

```bash
docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  test rules /etc/prometheus/tests/docgrid-operational-alerts.test.yml
```

결과: **SUCCESS**

검증 범위:

- 같은 DB Gauge를 두 Backend가 노출해도 cluster당 경보 1개 생성
- claim 가능 Job이 있고 Worker가 없을 때만 1분 후 경보
- Embedding·RAG·Outbox 임계값과 `for` 지속 시간
- 정상 RAG 처리 시간은 경보 제외
- Snapshot stale 지속 시간

## 전체 회귀 테스트

```bash
DB_PORT=55433 \
JWT_SECRET=docgrid-operational-metrics-test-secret-key-2026-with-at-least-32-bytes \
./gradlew test
```

결과: **1171 tests, 실패 0, errors 0, skipped 0**

추가 검증:

- `promtool check config`: 성공, rule file 3개·총 19개 규칙
- `promtool check rules`: 성공
- Backend·Pipeline·Operational 전체 rule suite: **SUCCESS**
- 기본 Compose와 monitoring profile `docker compose config --quiet`: 성공

## 실제 Management endpoint

격리 Schema에서 Backend를 `SERVER_PORT=18080`, `MANAGEMENT_PORT=18081`로 실행한 뒤
`GET /actuator/prometheus`를 확인했다.

- Queue·Worker Gauge 9개 노출
- `docgrid_operational_snapshot_age_seconds` 노출
- `docgrid_operational_snapshot_refresh_total{outcome="success|failed"}` 노출
- 빈 Queue의 수와 oldest age는 0
- 15초 주기 갱신 성공 Counter 증가, 실패 Counter 0
