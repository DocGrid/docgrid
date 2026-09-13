# DocGrid Prometheus

DocGrid가 함께 제공하는 Prometheus는 Host에서 실행한 Spring Backend와 Docker Compose의 Embedding
Provider를 수집한다. 기본 scrape 주기와 rule 평가 주기는 15초다.

## 번들 Prometheus 사용

Backend를 먼저 실행한다.

```bash
./backend/gradlew -p backend bootRun
```

Management endpoint는 기본적으로 Host의 `8081` 포트에서 열린다. 그다음 monitoring profile을
실행한다.

```bash
docker compose --profile monitoring up -d prometheus
```

Prometheus는 컨테이너에서 `host.docker.internal:8081`을 수집한다. Compose의 `extra_hosts`가
Linux의 host gateway를 같은 이름으로 연결하며 Docker Desktop도 같은 주소를 지원한다.

- Target 상태: <http://localhost:9090/targets>
- Alert 상태: <http://localhost:9090/alerts>

기본 Backend target과 배포 식별 label은
`monitoring/prometheus/targets/docgrid-backend.yml`에서 변경한다.

```yaml
- targets:
    - host.docker.internal:8081
  labels:
    environment: local
    cluster: docgrid-local
```

## 기존 Prometheus 사용

기존 Prometheus를 운영하는 환경에서는 다음 scrape job을 해당 Prometheus 설정에 추가한다.

```yaml
scrape_configs:
  - job_name: docgrid-backend
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - docgrid-backend.internal:8081
        labels:
          environment: production
          cluster: docgrid-production
```

`8081`은 사용자 API 포트가 아닌 Management 포트다. 방화벽, Security Group, 컨테이너 network로
Prometheus와 운영자만 접근하도록 제한한다.

DocGrid 경보를 함께 사용하려면 `monitoring/prometheus/rules/`의 규칙 파일을 기존 Prometheus의
`rule_files` 경로에 복사한다.

## 기본 Backend 경보

| 경보 | 조건 | 지속 시간 |
|---|---|---:|
| `DocGridBackendDown` | Backend scrape 실패 | 1분 |
| `DocGridDatabasePoolSaturated` | HikariCP active/max가 90% 초과 | 2분 |
| `DocGridBackendHighServerErrorRatio` | 5분간 20건 이상이며 5xx가 5% 초과 | 3분 |

HTTP 오류율에서는 Streamable HTTP 특성이 다른 `/mcp`를 제외한다. 이 경보들은 현재 Prometheus
화면에서 확인하며 외부 전달은 Alertmanager 설정을 추가한 뒤 활성화된다.

## 비동기 Pipeline 경보

| 경보 | 조건 | 지속 시간 |
|---|---|---:|
| `DocGridEmbeddingRetryableFailureRatioHigh` | 10분간 10회 이상 실행되고 retryable 실패가 10% 초과 | 5분 |
| `DocGridRagProviderFallbackSpike` | 10분간 provider fallback 3회 이상 | 1분 |
| `DocGridRagTimeoutSweepSpike` | 10분간 timeout 강제 종료 3회 이상 | 1분 |
| `DocGridSyncOutboxTerminalFailure` | 15분간 새로운 최종 실패 1회 이상 | 즉시 |
| `DocGridEmbeddingWorkersUnavailable` | claim 가능 Job이 있지만 유효 Worker가 없음 | 1분 |
| `DocGridEmbeddingQueueStalled` | 가장 오래된 claim 가능 Job 나이가 5분 초과 | 5분 |
| `DocGridRagQueueStalled` | 가장 오래된 PROCESSING 응답 나이가 150초 초과 | 30초 |
| `DocGridSyncOutboxQueueStalled` | 가장 오래된 claim 가능 Event 나이가 2분 초과 | 2분 |
| `DocGridOperationalSnapshotStale` | DB 운영 Snapshot을 1분 넘게 갱신하지 못함 | 1분 |

Counter는 DB 상태 전이를 수행한 Transaction이 커밋된 뒤에만 증가한다. Embedding 실패의
`failure_type`은 고정 enum이며 `retryable` label로 사용자 문서 오류와 운영 장애를 구분한다.
Job ID, 오류 메시지와 사용자 입력은 label에 포함하지 않는다.

### 현재 Queue 상태 Gauge

Backend는 기본 15초마다 별도 단일 Thread에서 Queue별 aggregate query를 실행하고 마지막 정상 결과를
메모리에 보관한다. `/actuator/prometheus`의 Gauge callback은 이 메모리만 읽으므로 scrape 횟수가 DB
조회 횟수를 늘리지 않는다. 주기는 `MANAGEMENT_METRICS_SNAPSHOT_INTERVAL`로 조정할 수 있다.

`PENDING` 전체 개수가 아니라 현재 시각에 실제 claim 가능한 항목만 backlog에 포함한다. Embedding
Retry는 `next_retry_at`, Sync Outbox는 `available_at`이 미래이면 제외한다. 가장 오래된 항목의 나이도
최초 생성 시각과 Retry 실행 가능 시각을 구분해 계산한다.

| Metric | 의미 |
|---|---|
| `docgrid_embedding_claimable_jobs` | 지금 claim 가능한 Embedding Job 수 |
| `docgrid_embedding_processing_jobs` | 처리 중인 Embedding Job 수 |
| `docgrid_embedding_oldest_claimable_age_seconds` | 가장 오래된 claim 가능 Job의 대기 시간 |
| `docgrid_embedding_active_workers` | Heartbeat가 만료되지 않은 ACTIVE·IDLE Worker 수 |
| `docgrid_rag_processing_jobs` | PROCESSING RAG 응답 수 |
| `docgrid_rag_oldest_processing_age_seconds` | 가장 오래된 PROCESSING 응답의 나이 |
| `docgrid_sync_outbox_claimable_events` | 지금 claim 가능한 Sync Outbox Event 수 |
| `docgrid_sync_outbox_processing_events` | 처리 중인 Sync Outbox Event 수 |
| `docgrid_sync_outbox_oldest_claimable_age_seconds` | 가장 오래된 claim 가능 Event의 대기 시간 |
| `docgrid_operational_snapshot_age_seconds` | 마지막 정상 DB Snapshot 이후 경과 시간 |
| `docgrid_operational_snapshot_refresh_total{outcome}` | Snapshot 갱신 성공·실패 횟수 |

여러 Backend가 같은 DB를 수집하면 동일 Gauge가 인스턴스 수만큼 노출된다. 번들 규칙은 이 값을
합산하지 않고 `max by (cluster, environment)`로 평가해 backlog를 중복 계산하지 않는다. 갱신 실패는
마지막 정상 값을 유지하며, 첫 성공 전과 장시간 실패는 `snapshot_age` 경보로 드러난다.

## 설정 검증

로컬에 promtool을 설치하지 않아도 고정된 Prometheus 이미지로 검사할 수 있다.

```bash
docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  check config /etc/prometheus/prometheus.yml

docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  check rules \
  /etc/prometheus/rules/embedding-provider-alerts.yml \
  /etc/prometheus/rules/docgrid-backend-alerts.yml \
  /etc/prometheus/rules/docgrid-pipeline-alerts.yml

docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  test rules /etc/prometheus/tests/docgrid-backend-alerts.test.yml

docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  test rules /etc/prometheus/tests/docgrid-pipeline-alerts.test.yml

docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  test rules /etc/prometheus/tests/docgrid-operational-alerts.test.yml

docker compose config
```
