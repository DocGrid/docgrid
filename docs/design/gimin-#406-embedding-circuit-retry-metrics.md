# Embedding Circuit·지연 재시도 관측성 설계 (#406)

closes #406

## 1. 배경

Embedding Provider Circuit 상태는 Backend JVM 메모리에만 있어 로그 시점 외에는 현재 상태를 확인할 수
없다. 기존 Queue Snapshot도 지금 Claim 가능한 Job만 집계하므로, 장애로 `next_retry_at`이 미래에 예약된
Job이 누적돼도 `claimable_jobs=0`으로 보일 수 있다.

## 2. 목표와 비목표

### 목표

- JVM별 Circuit 보호 활성 상태와 상태 전환 횟수를 Micrometer로 노출한다.
- 즉시 Claim 가능한 Job과 미래 재시도 Job을 같은 관측 시각에서 분리 집계한다.
- Circuit 보호가 1분 이상 지속되면 Prometheus가 Cluster별 경보를 한 건 생성한다.

### 비목표

- 여러 Backend JVM이 공유하는 분산 Circuit 상태
- delayed retry Job 존재 자체에 대한 경보
- Grafana Dashboard와 Provider 장애 Drill
- Sync Outbox Retry Jitter

## 3. Metric 계약

| Prometheus Metric | Type | 의미 |
|---|---|---|
| `docgrid_embedding_provider_circuit_open` | Gauge | OPEN 또는 HALF_OPEN이면 `1`, CLOSED이면 `0` |
| `docgrid_embedding_provider_circuit_transitions_total{state}` | Counter | 목적 상태별 전환 횟수 |
| `docgrid_embedding_delayed_retry_jobs` | Gauge | `PENDING`이며 `next_retry_at > observedAt`인 Job 수 |

`state`는 `open`, `half_open`, `closed` 세 값으로 고정한다. Job ID, 오류 메시지, 사용자 입력은 label에
포함하지 않는다.

Gauge가 HALF_OPEN에서도 `1`을 유지하는 이유는 Probe 성공 전까지 일반 호출이 계속 차단되기 때문이다.
Probe 시작 순간 Gauge를 `0`으로 바꾸면 Provider가 계속 실패하더라도 Prometheus의 `for: 1m` 시간이
초기화될 수 있다. 실제 상태 전이는 Counter로 별도 확인한다.

## 4. 수집 흐름

```text
EmbeddingProviderCircuitBreaker
  → synchronized 상태 전환 확정
    → EmbeddingProviderCircuitMetrics
      → 현재 보호 활성 Gauge 갱신
      → 목적 상태 Counter 증가

OperationalMetricsSnapshotRefresher (기본 15초)
  → 동일 observedAt으로 aggregate SQL 실행
    → claimable: next_retry_at IS NULL OR next_retry_at <= observedAt
    → delayed:   next_retry_at > observedAt
  → 전체 Query 성공 시 AtomicReference Snapshot 교체

Prometheus scrape
  → JVM Circuit Metric과 메모리 Snapshot만 읽음
  → scrape 시 DB 조회 없음
```

Circuit 전이와 Metric 갱신은 같은 `synchronized` 경계에서 수행한다. delayed retry 수는 새 Scheduler를
추가하지 않고 기존 Embedding Snapshot SQL에 집계 열 하나를 추가한다. 기존
`(status, next_retry_at)` Index가 두 조건을 지원한다.

## 5. 경보

`DocGridEmbeddingProviderCircuitOpen`은 Cluster 안에서 한 Backend라도 보호 상태가 활성화되면 Pending을
시작한다. 기본 OPEN 시간이 30초이므로 `for: 1m`을 적용해 한 번의 일시적 OPEN은 알리지 않고, Probe가
성공하지 못해 보호가 지속되는 경우를 알린다.

```promql
max by (cluster, environment) (
  docgrid_embedding_provider_circuit_open
) == 1
```

여러 Backend가 독립 Circuit을 가지므로 `sum`으로 인스턴스 수를 세지 않는다. delayed retry Gauge는 정상
재시도 중에도 증가할 수 있어 이번 범위에서는 단독 경보 조건으로 사용하지 않는다.

## 6. 검증 기준

- 실패 임계치 도달 시 Gauge `1`, `state=open` Counter 1회 증가
- Open 기간 뒤 단일 Probe 진입 시 `state=half_open` Counter 1회 증가, Gauge `1` 유지
- Probe 성공 시 Gauge `0`, `state=closed` Counter 1회 증가
- Probe 실패 시 `state=open` Counter 재증가, Gauge `1` 유지
- 같은 관측 시각의 즉시 실행 Job 3건과 미래 재시도 Job 1건 분리 집계. 경계와 같은 시각은 즉시 실행에 포함
- Prometheus rule test에서 45초에는 미발생, 1분에는 발생, CLOSED 전환 뒤 해제
