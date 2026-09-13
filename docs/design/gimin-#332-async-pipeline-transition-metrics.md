# 비동기 Pipeline 상태 전이 메트릭 설계 (#332)

closes #332

## 문제

Actuator가 제공하는 JVM·HTTP·HikariCP 메트릭만으로는 DocGrid의 비동기 처리 결과를 알 수 없다.
인덱싱, RAG, Sync Outbox는 실패가 누적 상태로 남거나 재시도를 위해 다시 `PENDING`으로 돌아가므로
현재 `FAILED` 행 수를 경보 기준으로 사용하면 새 장애와 과거 장애를 구분할 수 없다.

운영 경보에는 일정 시간 동안 새로 확정된 상태 전이 횟수가 필요하다. 이 문서는 Counter 이름과
label, 증가 시점, 이를 사용하는 Prometheus 규칙을 고정한다.

## 상태 전이와 Counter의 Commit 경계

도메인 서비스는 DB 상태를 바꾸는 Transaction 안에서 제한된 metric event를 발행한다.
`AsyncPipelineMetrics`는 `@TransactionalEventListener(AFTER_COMMIT)`으로 이벤트를 받는다.

```text
Transactional command
  → 상태 전이와 감사 이력 저장
  → 제한된 outcome event 발행
  → commit 성공
  → AFTER_COMMIT listener
  → Micrometer Counter 증가
```

Transaction이 롤백되면 listener는 호출되지 않는다. 조건부 UPDATE가 경합에서 0건을 반환한 RAG
완료·실패 경로는 이벤트 자체를 발행하지 않는다. 멱등 재생도 최초 상태를 반환할 뿐 새로운 전이가
아니므로 Counter를 다시 올리지 않는다.

## Metric 계약

Micrometer 이름은 Prometheus endpoint에서 아래 `_total` Counter로 노출된다.

| Prometheus metric | label | 허용 값 |
|---|---|---|
| `docgrid_embedding_job_attempts_total` | `outcome` | `success`, `retry_scheduled`, `terminal_failure` |
|  | `failure_type` | `NONE`, `IndexingFailureType` 12종, `WORKER_LEASE_EXPIRED` |
|  | `retryable` | `true`, `false` |
| `docgrid_rag_job_completions_total` | `outcome` | `success`, `no_context`, `provider_fallback`, `timeout_swept`, `unexpected_failure` |
| `docgrid_sync_event_attempts_total` | `outcome` | `processed`, `retry_scheduled`, `terminal_failure`, `lease_recovered` |

ID, 사용자 정보, 문서명, 오류 메시지는 label에 넣지 않는다. 실패 유형과 결과는 코드의 enum에서만
생성해 입력 크기에 따라 시계열 수가 늘어나지 않게 한다. `failure_type`은 `IndexingFailureType`의
모든 값을 exhaustive switch로 매핑하고, `retryable`은 도메인 enum의 정책값을 그대로 사용한다.
실패 유형 추가 시 누락은 컴파일 오류가 되고 Retry 정책은 한 곳에서만 관리된다.

## 도메인별 기록 위치

### Embedding

- `DocumentIndexingCompletionService`: 새 실행이 `INDEXED`로 확정되면 `success`
- `DocumentIndexingFailureService`: 전이 후 상태가 `PENDING`이면 `retry_scheduled`, `FAILED`면
  `terminal_failure`
- `EmbeddingJobLeaseRecoveryService`: 같은 결과 구분에 `WORKER_LEASE_EXPIRED` 실패 유형 사용

완료·실패 요청의 멱등 재생 분기는 이벤트 발행 전에 반환한다.

### RAG

- 검색 후보 없음: `no_context`
- Ollama 정상 응답 저장: `success`
- Ollama 예외 뒤 extractive fallback 저장: `provider_fallback`
- Worker의 예상하지 못한 실패 확정: `unexpected_failure`
- Timeout Sweeper의 조건부 UPDATE 성공: `timeout_swept`

RAG 이벤트는 `forceFailIfProcessing` 또는 `completeSuccessIfProcessing`의 영향 행이 1건일 때만
발행한다. Worker와 Sweeper가 경쟁해도 승리한 한 경로만 기록된다.

### Sync Outbox

- Handler 부작용과 Event 완료를 함께 커밋: `processed`
- 실패 후 backoff 예약: `retry_scheduled`
- 재시도 소진: `terminal_failure`
- 만료 Lease를 다시 `PENDING`으로 회수: `lease_recovered`

Lease 회수가 재시도 한도를 소진해 `FAILED`로 끝나면 즉시 대응할 수 있도록
`terminal_failure`로 기록한다.

## 경보 정책

| 경보 | 목적 | 오탐 방지 |
|---|---|---|
| `DocGridEmbeddingRetryableFailureRatioHigh` | 인프라성 인덱싱 실패 증가 | retryable만 포함, 10분간 최소 10회, 10% 초과가 5분 지속 |
| `DocGridRagProviderFallbackSpike` | Ollama 장애 또는 지연 증가 | 10분간 3회 이상, 1분 지속 |
| `DocGridRagTimeoutSweepSpike` | Worker 정체로 Sweeper 회수 증가 | 10분간 3회 이상, 1분 지속 |
| `DocGridSyncOutboxTerminalFailure` | 새 최종 실패 즉시 발견 | 누적 FAILED 개수 대신 15분 `increase` 사용 |

`DOCUMENT_CONTENT_INVALID`처럼 retryable이 아닌 사용자 문서 오류는 인덱싱 운영 장애 경보에서
제외한다. 성공 Counter만 증가하거나 Outbox가 재시도 중인 경우에도 최종 실패 경보는 발생하지 않는다.

## 선택한 방식과 한계

도메인 서비스가 `MeterRegistry`를 직접 사용하면 상태 저장이 롤백돼도 이미 증가한 Counter를 되돌릴
수 없다. 호출자에서 Transaction 반환 뒤 증가시키는 방식도 가능하지만 HTTP Controller, Scheduler,
내부 Worker마다 같은 규칙을 반복해야 한다. Transaction event를 사용하면 상태 전이와 metric event의
결합 지점을 도메인 서비스 한 곳에 두고 실제 증가는 commit 이후로 미룰 수 있다.

Counter는 프로세스 메모리에 있으므로 Backend 재시작 때 0부터 시작한다. Prometheus가 주기적으로
누적 시계열을 저장하고 `rate`·`increase`에서 Counter reset을 처리한다는 운영 모델을 따른다.
