# 비동기 Pipeline 경보 대응

## 공통 확인

1. Alertmanager에서 `cluster`, `environment`, `service`, `dependency` label과 inhibition 여부를 확인한다.
2. Prometheus 경보 상세에서 현재 값, `for` 지속 시간, 관련 metric의 최근 변화를 확인한다.
3. 배포, DB, Redis, Embedding Provider 변경 시각과 경보 시작 시각을 비교한다.
4. 원인을 복구한 뒤 Queue 수와 oldest age가 줄고 경보가 resolved로 전달되는지 확인한다.

## Embedding

`EmbeddingProviderDown`과 `EmbeddingProviderNotReady`는 Provider endpoint 또는 모델 준비 상태를 먼저
복구한다. 이 critical 경보가 firing인 동안 같은 Provider warning과 의존 경보는 억제될 수 있다.

`DocGridEmbeddingWorkersUnavailable`은 claim 가능한 Job이 있는데 유효 Heartbeat를 가진 Worker가
없다는 뜻이다. Worker 프로세스, 마지막 Heartbeat, Lease와 재시작 이력을 확인한다.

`DocGridEmbeddingRetryableFailureRatioHigh`와 `DocGridEmbeddingQueueStalled`은 `failure_type`, Worker
로그, Provider 상태, Object Storage 접근을 함께 확인한다. 미래 `next_retry_at` Job은 claimable
backlog에 포함되지 않는다.

## RAG

`DocGridRagProviderFallbackSpike`은 Ollama 연결·응답 지연·timeout을 확인한다.
`DocGridRagTimeoutSweepSpike`와 `DocGridRagQueueStalled`은 Worker 실행 여부, PROCESSING 행의 생성
시각, timeout sweeper 실행 기록을 확인한다.

## Sync Outbox

`DocGridSyncOutboxTerminalFailure`은 재시도를 소진한 새 Event가 발생한 상태다. 실패한 event type과
오류 이력, 대상 데이터의 현재 상태를 확인한 뒤 관리자 재처리 절차를 사용한다.
`DocGridSyncOutboxQueueStalled`은 현재 `available_at`이 지난 Event만 대상으로 하므로 Handler,
Scheduler, DB lock과 Lease 회수 기록을 확인한다.

## Snapshot stale

`DocGridOperationalSnapshotStale`은 Queue가 정상이라는 뜻이 아니라 Backend가 운영 Snapshot을 1분
넘게 갱신하지 못했다는 뜻이다. Backend 로그의 `[OBSERVABILITY]` 경고, DB 연결과 pool 상태를 먼저
확인한다. 복구 전까지 마지막 정상 Gauge 값은 유지된다.

## 복구 완료 조건

- 원인 경보와 파생 경보가 inactive 또는 resolved다.
- claimable·processing 수와 oldest age가 정상 범위로 돌아온다.
- `docgrid_operational_snapshot_age_seconds`가 갱신 주기 근처로 유지된다.
- Alertmanager receiver 로그에 반복 실패가 없고 resolved 알림이 도착한다.
