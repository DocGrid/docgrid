# 비동기 Queue 운영 상태 Gauge 설계 (#334)

closes #334

## 문제

상태 전이 Counter는 최근 성공·재시도·실패 증가를 보여주지만 현재 Queue가 줄고 있는지는 보여주지
않는다. DB의 전체 `PENDING` 수를 그대로 노출하면 Retry backoff로 아직 실행할 수 없는 항목도 backlog로
계산된다. 또한 Gauge가 scrape 순간 Repository를 조회하면 Prometheus 수집 빈도와 Backend 인스턴스 수에
비례해 DB 부하가 증가한다.

## 수집 경계

```text
전용 단일 Thread (기본 15초)
  → 짧은 read-only Transaction
    → Embedding + 유효 Worker aggregate SQL 1회
    → RAG aggregate SQL 1회
    → Sync Outbox aggregate SQL 1회
  → 세 Query가 모두 성공하면 AtomicReference를 한 번에 교체

Prometheus scrape
  → Micrometer Gauge
  → AtomicReference와 Clock만 읽음
  → DB 조회 0회
```

집계 Transaction은 2초 timeout을 사용한다. 한 Query라도 실패하면 새 결과를 버리고 마지막 정상
Snapshot을 유지한다. 이를 0으로 바꾸면 실제 backlog가 사라진 것처럼 보이므로 실패 Counter와 마지막
정상 갱신 이후 시간으로 별도 감시한다.

## Queue별 의미

### Embedding

- claim 가능: `status = PENDING`이며 `next_retry_at IS NULL OR next_retry_at <= now`
- oldest 기준: 최초 실행은 `created_at`, Retry는 `next_retry_at`
- 처리 중: `status = PROCESSING`
- 유효 Worker: 상태가 `ACTIVE` 또는 `IDLE`이고 `last_heartbeat_at > now - dead-threshold`

오래전에 생성된 Job이 backoff 종료 직후 즉시 오래된 backlog로 판정되지 않도록 Retry Job의 대기 나이는
`next_retry_at`부터 계산한다.

### RAG

- 처리 중: `status = PROCESSING`
- oldest 기준: `created_at`

RAG는 현재 별도 대기 상태 없이 PROCESSING 생성 후 단일 Worker가 처리하므로 PROCESSING 자체가 Queue다.

### Sync Outbox

- claim 가능: `status = PENDING`이며 `available_at <= now`
- oldest 기준: `available_at`
- 처리 중: `status = PROCESSING`

## Metric 계약

| Metric | Type | 의미 |
|---|---|---|
| `docgrid_embedding_claimable_jobs` | Gauge | 현재 claim 가능한 Embedding Job 수 |
| `docgrid_embedding_processing_jobs` | Gauge | 처리 중인 Embedding Job 수 |
| `docgrid_embedding_oldest_claimable_age_seconds` | Gauge | 가장 오래된 claim 가능 Job의 대기 시간 |
| `docgrid_embedding_active_workers` | Gauge | Heartbeat가 유효한 ACTIVE·IDLE Worker 수 |
| `docgrid_rag_processing_jobs` | Gauge | PROCESSING RAG 응답 수 |
| `docgrid_rag_oldest_processing_age_seconds` | Gauge | 가장 오래된 PROCESSING 응답의 나이 |
| `docgrid_sync_outbox_claimable_events` | Gauge | 현재 claim 가능한 Outbox Event 수 |
| `docgrid_sync_outbox_processing_events` | Gauge | 처리 중인 Outbox Event 수 |
| `docgrid_sync_outbox_oldest_claimable_age_seconds` | Gauge | 가장 오래된 claim 가능 Event의 대기 시간 |
| `docgrid_operational_snapshot_age_seconds` | Gauge | 마지막 정상 Snapshot 갱신 이후 시간 |
| `docgrid_operational_snapshot_refresh_total{outcome}` | Counter | `success`, `failed` 갱신 결과 |

동적 식별자나 오류 메시지 label은 없다. 여러 Backend가 같은 DB를 읽으면 동일한 Gauge가 인스턴스별로
노출되므로 Prometheus 규칙은 `sum` 대신 `max by (cluster, environment)`를 사용한다.

## 경보

| 경보 | 조건 | 지속 시간 |
|---|---|---:|
| `DocGridEmbeddingWorkersUnavailable` | claim 가능 Job > 0, 유효 Worker = 0 | 1분 |
| `DocGridEmbeddingQueueStalled` | oldest claim 가능 Job > 300초 | 5분 |
| `DocGridRagQueueStalled` | oldest PROCESSING 응답 > 150초 | 30초 |
| `DocGridSyncOutboxQueueStalled` | oldest claim 가능 Event > 120초 | 2분 |
| `DocGridOperationalSnapshotStale` | 마지막 정상 Snapshot > 60초 | 1분 |

`snapshot_age`는 첫 정상 갱신 전 `+Inf`다. Backend가 올라왔지만 DB 집계를 한 번도 완료하지 못한 경우도
수집 정상으로 오인하지 않기 위해서다.
