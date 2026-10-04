# Embedding Circuit·지연 재시도 관측성 설계 (#406)

closes #406

## 1. 배경

Embedding Provider Circuit 상태는 Backend JVM 메모리에만 있어 로그 시점 외에는 현재 상태를 확인할 수
없다. 기존 Queue Snapshot도 지금 Claim 가능한 Job만 집계하므로, 장애로 `next_retry_at`이 미래에 예약된
Job이 누적돼도 `claimable_jobs=0`으로 보일 수 있다.

## 2. 목표와 비목표

### 목표

- JVM별 Circuit 보호 활성 상태와 상태 전환·Half-open Probe 결과를 Micrometer로 노출한다.
- 즉시 Claim 가능한 Job과 미래 재시도 Job을 같은 관측 시각에서 분리 집계한다.
- Half-open Probe 실패로 Provider 장애 지속이 확인된 뒤 Circuit이 1분간 닫히지 않으면
  Prometheus가 Cluster별 경보를 한 건 생성하고, 새로운 실패 증거가 없으면 유한 시간 안에 해제한다.

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
| `docgrid_embedding_provider_circuit_probe_total{outcome}` | Counter | Half-open Probe의 성공·실패 횟수 |
| `docgrid_embedding_provider_circuit_probe_failed` | Gauge | 현재 Circuit 보호 주기에서 Probe 실패가 확인됐고 아직 성공하지 못했으면 `1` |
| `docgrid_embedding_delayed_retry_jobs` | Gauge | `PENDING`이며 `next_retry_at > observedAt`인 Job 수 |

`state`는 `open`, `half_open`, `closed`, `outcome`은 `success`, `failed`로 고정한다. Job ID,
오류 메시지, 사용자 입력은 label에 포함하지 않는다. 두 Probe Counter는 애플리케이션
시작 시 0으로 미리 등록해 첫 결과도 Prometheus가 관측한다.

Gauge가 HALF_OPEN에서도 `1`을 유지하는 이유는 Probe 성공 전까지 일반 호출이 계속 차단되기 때문이다.
Probe 시작 순간 Gauge를 `0`으로 바꾸면 Provider가 계속 실패하더라도 Prometheus의 `for: 1m` 시간이
초기화될 수 있다. 실제 상태 전이는 Counter로 별도 확인한다.

`probe_failed` Gauge는 실패 Probe에서 `1`이 되고 성공 Probe에서 `0`이 된다. 경보는 이 Gauge와
최근 6분의 `increase(probe_total{outcome="failed"})`를 함께 사용한다. Gauge가 현재 보호 주기를
구분하므로 이전 Circuit 주기의 실패가 새로운 최초 OPEN에 섞이지 않고, Counter 시간 창이 트래픽
중단 뒤 오래된 실패 증거를 무기한 유지하지 않게 한다.

## 4. 수집 흐름

```text
EmbeddingProviderCircuitBreaker
  → synchronized 상태 전환 확정
    → EmbeddingProviderCircuitMetrics
      → 현재 보호 활성 Gauge 갱신
      → 목적 상태 Counter 증가
      → Half-open Probe 결과 Counter 증가
      → Probe 실패 확인 Gauge 설정·해제

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

`DocGridEmbeddingProviderCircuitOpen`은 같은 Backend JVM에서 Circuit 보호가 활성화돼 있고,
현재 보호 주기의 Half-open Probe 실패가 확인됐으며, 최근 6분 안에 실제 실패 Counter 증가가 있는
경우에만 Pending을 시작한다. 최초 OPEN 후 트래픽이 없어 Probe가 실행되지 않은 JVM은 Provider 장애
지속의 증거가 없으므로 경보하지 않는다. 이 조건이 1분간 유지될 때 경보한다.

```promql
max by (cluster, environment) (
  (
    (docgrid_embedding_provider_circuit_open == 1)
    and on (instance, cluster, environment)
    (docgrid_embedding_provider_circuit_probe_failed == 1)
  )
  and on (instance, cluster, environment)
  (
    increase(docgrid_embedding_provider_circuit_probe_total{
      outcome="failed"
    }[6m]) > 0
  )
) == 1
```

세 시계열은 `instance`, `cluster`, `environment`로 일치시켜 다른 JVM의 OPEN·Probe 실패·Counter가
결합되지 않게 한다. 여러 Backend가 독립 Circuit을 가지므로 `sum`으로 인스턴스 수를 세지 않는다.

6분 시간 창은 기본 `INDEXING_WORKER_RETRY_MAX_DELAY=5m`보다 길다. Worker만 Embedding 호출을
만드는 환경에서도 약 5분 간격의 다음 Probe 실패를 놓치지 않으면서, 단일 실패 뒤 호출이 끊긴 JVM의
경보는 무기한 유지하지 않는다. 운영에서 Retry 최대 지연을 5분보다 늘리면 이 시간 창도 그보다 길게
조정해야 한다. 트래픽이 끊긴 상태에서 6분 뒤 경보가 해제되는 것은 Provider 회복을 확인했다는 뜻이
아니라 최근 실패 증거가 만료됐다는 뜻이다. 성공 Probe가 발생하면 두 Gauge가 즉시 0이 되어 시간 창을
기다리지 않고 해제된다.

delayed retry Gauge는 정상 재시도 중에도 증가할 수 있어 이번 범위에서는 단독 경보 조건으로 사용하지
않는다.

## 6. 검증 기준

- 실패 임계치 도달 시 Gauge `1`, `state=open` Counter 1회 증가
- Open 기간 뒤 단일 Probe 진입 시 `state=half_open` Counter 1회 증가, Open Gauge `1` 유지
- Probe 성공 시 `outcome=success` Counter 증가, Open·Probe 실패 Gauge `0`, `state=closed` Counter 증가
- Probe 실패 시 `outcome=failed` Counter와 `state=open` Counter 재증가, 두 Gauge `1` 유지
- 같은 관측 시각의 즉시 실행 Job 3건과 미래 재시도 Job 1건 분리 집계. 경계와 같은 시각은 즉시 실행에 포함
- Prometheus rule test에서 최초 OPEN은 무경보, Probe 실패 1분 후 발생, CLOSED 전환 시 즉시 해제
- 단일 실패 뒤 호출이 끊기면 최근 실패 시간 창 만료 후 해제
- 5분 간격으로 Probe가 계속 실패해도 경보 유지, 이전 보호 주기의 Probe 실패는 새 OPEN에 미전파
