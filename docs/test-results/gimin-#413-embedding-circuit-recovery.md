# Embedding Circuit 장애·Probe 복구 경로 통합 검증 결과

## 1. 결론

2026-10-04(KST)에 커밋 `310965b929cff4e682d029ff1bb836379c1fe3ae`의 Drill로 실제
Spring Backend, PostgreSQL, Valkey, BGE-M3, Prometheus, Alertmanager를 연결했다. Provider 중단부터
Circuit OPEN, 실패 HALF_OPEN Probe, 경보 억제, Provider 재시작, 성공 Probe, Job `INDEXED`까지
하나의 Job으로 검증했고 최종 결과는 PASS다.

| 검증 항목 | 실측 결과 | 판정 |
|---|---:|---|
| Provider 중단 → Circuit OPEN | 38.370초 | PASS |
| Provider 중단 → 실패 Probe | 78.577초 | PASS |
| 실패 Probe → Circuit 경보 firing | 75.299초 | PASS |
| Circuit OPEN 전환 | 2회 | 최초 OPEN + 실패 Probe 재OPEN |
| HALF_OPEN 전환 | 2회 | 실패 Probe + 성공 Probe |
| 실패 / 성공 Probe | 1회 / 1회 | PASS |
| Circuit CLOSED 전환 | 1회 | PASS |
| Circuit warning firing webhook | 0건 | Provider critical에 의해 억제 |
| Provider 복구 → ready | 4.282초 | PASS |
| ready → 성공 Probe | 4.148초 | PASS |
| Provider 복구 → Job `INDEXED` | 8.509초 | PASS |
| 저장 Embedding | 1건 | PASS |
| 최종 delayed Retry | 0건 | PASS |
| Backend 전체 테스트 | 1,315개 중 1,313개 성공 | 실패·오류 0, 조건부 skip 2 |
| Monitoring E2E | firing 3초, resolved 2초 | PASS |

최종 원본 증거는 다음 두 파일이다.

- [`circuit-recovery.json`](evidence/issue-413/circuit-recovery.json)
- [`circuit-recovery-webhooks.jsonl`](evidence/issue-413/circuit-recovery-webhooks.jsonl)

## 2. 검증한 전체 구조

### 그림 1. 실제 컴포넌트를 연결한 End-to-End 경로

```text
[로그인·문서 업로드 API]
          │
          │ 실제 multipart TXT 업로드
          ▼
[Observer Backend: Worker OFF]
          │
          ├─ document_versions / document_chunks 저장
          └─ embedding_jobs(PENDING) 생성
                         │
                         ▼
                [PostgreSQL Queue]
                         │ claim
                         ▼
 [Worker Backend] ── acquirePermission() ──> [EmbeddingProviderCircuitBreaker]
      │                                          │
      │ POST /embed/batch                        │ OPEN / HALF_OPEN / CLOSED
      ▼                                          │ Metric 갱신
 [실제 BGE-M3 Provider]                          ▼
      │                                   [/actuator/prometheus]
      │ /metrics                                  │
      └───────────────────────────────────────────┤ scrape 15초
                                                  ▼
                                            [Prometheus]
                                                  │ rule 평가 15초
                                                  ▼
                                            [Alertmanager]
                                                  │ inhibition
                                                  ▼
                                      [격리 Webhook Receiver]
                                      firing/resolved 증거 기록
```

Mock Worker나 Mock Provider를 사용하지 않았다. Job은 실제 업로드 API로 만들었고, Worker는 실제 문서
Chunk를 읽어 BGE-M3 Batch API를 호출했다. Prometheus와 Alertmanager도 운영 규칙 파일을 그대로 읽었다.

## 3. 장애·복구 절차

### 그림 2. 장애 주입부터 최종 복구까지의 실행 순서

```text
1. Observer Backend 기동
   └─ Worker OFF
      └─ 업로드 API → Job #1 PENDING

2. Provider 중단(docker stop)
   └─ 고정된 loopback host port는 유지

3. Worker Backend 기동
   ├─ attempt 1: 연결 실패 → retry_count 1
   ├─ attempt 2: 연결 실패 → retry_count 2
   └─ attempt 3: 연결 실패 → retry_count 3 → Circuit OPEN

4. OPEN 30초 경과
   └─ attempt 4: HALF_OPEN Probe 권한 획득
      └─ 연결 실패 → failed Probe 1 → Circuit 재OPEN

5. 경보 검증용 Job 동결
   └─ UPDATE ... WHERE status='PENDING' RETURNING id
      └─ next_retry_at = now + 10분

6. 운영 경보 대기
   ├─ EmbeddingProviderDown = firing
   └─ DocGridEmbeddingProviderCircuitOpen = firing + suppressed

7. Provider 재시작
   ├─ 같은 host port로 ready
   └─ Job next_retry_at = now

8. 복구
   ├─ HALF_OPEN 성공 Probe 1
   ├─ Circuit CLOSED
   ├─ Embedding 1건 저장
   ├─ Job INDEXED
   └─ delayed Retry Gauge 0
```

`pause/unpause`는 사용하지 않았다. pause 중 Java HTTP 요청이 timeout되어도 연결 자체는 남을 수 있고,
unpause 뒤 오래된 요청이 Provider에 도달할 수 있기 때문이다. 실행 시작 때 비어 있는 loopback port를
하나 선택해 Compose에 고정하고, `stop/start`로 연결 거부와 정상 복구를 만들었다.

## 4. Circuit 내부 상태와 Metric 검증

### 그림 3. 상태 전이와 관측값

```text
                  연속 실패 3회
 [CLOSED] ─────────────────────────> [OPEN #1]
    │                                  │
    │ open=0                           │ open=1
    │ probe_failed=0                   │ open transitions=1
    │                                  │ delayed retry=1
    │                                  │
    │                          open-duration 30초
    │                                  │
    │                                  ▼
    │                            [HALF_OPEN #1]
    │                                  │ failed Probe
    │                                  ▼
    │                             [OPEN #2]
    │                                  │ probe_failed=1
    │                                  │ failed probes=1
    │                                  │ open transitions=2
    │                                  │
    │                          Provider 재시작
    │                                  │
    │                                  ▼
    │                            [HALF_OPEN #2]
    │                                  │ successful Probe
    └──────────────────────────────────┘
                  [CLOSED]
                  open=0
                  probe_failed=0
                  successful probes=1
                  closed transitions=1
                  delayed retry=0
```

| Snapshot | open | probe failed | OPEN 전환 | HALF_OPEN 전환 | CLOSED 전환 | 실패 Probe | 성공 Probe | delayed Retry |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 최초 OPEN | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 1 |
| 실패 Probe | 1 | 1 | 2 | 1 | 0 | 1 | 0 | 1 |
| 성공 Probe | 0 | 0 | 2 | 2 | 1 | 1 | 1 | 0 |
| 최종 recovered | 0 | 0 | 2 | 2 | 1 | 1 | 1 | 0 |

성공 Probe 직후에도 Job 완료 Transaction과 주기적 Gauge 갱신 사이에 짧은 시차가 생길 수 있다. 그래서
Job `INDEXED`와 Embedding 저장을 확인한 뒤 `delayedRetryJobs=0`인 별도 `recovered` snapshot까지
기다리도록 했다.

## 5. Prometheus와 Alertmanager 검증

### 그림 4. Root-cause 경보와 파생 경보 억제

```text
[Provider stop]
      │
      ├─ Prometheus up{job="embedding-provider"}=0
      │       └─ for: 1m
      │          └─ EmbeddingProviderDown{severity="critical"}=firing
      │
      └─ 실패 Probe
              └─ circuit_open=1 AND probe_failed=1
                 AND 최근 6분 failed Probe 존재
                    └─ for: 1m
                       └─ DocGridEmbeddingProviderCircuitOpen
                          {severity="warning",
                           dependency="embedding-provider"}=firing

[Alertmanager inhibition]
      critical root cause와 warning의 cluster/environment/dependency 일치
      ├─ Provider critical → webhook firing 1건
      └─ Circuit warning → suppressed, firing webhook 0건

[Provider recovery]
      ├─ up=1
      ├─ 성공 Probe → Circuit CLOSED
      ├─ Prometheus 두 경보 해제
      └─ Provider critical → webhook resolved 1건
```

| 경계 | 실측 | 해석 |
|---|---:|---|
| Provider stop → Prometheus `up=0` 관측 | 19.075초 | 15초 scrape 정렬 포함 |
| 실패 Probe → Circuit 경보 firing | 75.299초 | `for: 1m` + 15초 평가 정렬 |
| Circuit 경보 상태 | suppressed | root-cause inhibition 적용 |
| 35초 검증 창의 Circuit firing webhook | 0건 | warning 미전달 |
| Provider critical firing webhook | 1건 | 실제 Alertmanager 전달 |
| Provider critical resolved webhook | 1건 | 실제 Alertmanager 전달 |
| 복구 시작 → Prometheus 경보 모두 해제 | 30.720초 | scrape/evaluation 정렬 포함 |
| 복구 시작 → resolved webhook | 55.749초 | test `group_interval: 30s` 포함 |

Webhook Receiver는 JSONL을 쓰기 전에 `generatorURL`과 `externalURL`의 Docker 내부 주소를
`[internal URL omitted]`으로 치환한다. 경보 상태, 라벨, 시각, fingerprint는 유지한다.

## 6. 통합 검증이 발견한 실제 HTTP 계약 버그

### 그림 5. 수정 전 XML 선택과 수정 후 JSON 고정

```text
수정 전

EmbeddingClient
  .post()
  .uri("/embed/batch")
  .body(EmbedBatchRequest)
          │ Content-Type 미지정
          ▼
Spring MessageConverter 선택
          │ classpath의 XML Converter가 DTO 처리
          ▼
<EmbedBatchRequest>
  <texts>...</texts>
  <batch_size>2</batch_size>
</EmbedBatchRequest>
          │
          ▼
FastAPI EmbedBatchRequest(JSON object 기대)
          └─ HTTP 422 model_attributes_type
             └─ Job EMBEDDING_REQUEST_INVALID / terminal

수정 후

EmbeddingClient
  .contentType(MediaType.APPLICATION_JSON)
  .body(EmbedBatchRequest)
          │
          ▼
{"texts":[...],"batch_size":2}
          │
          ▼
FastAPI 200
  └─ 성공 Probe → Circuit CLOSED
     └─ Embedding 저장 → Job INDEXED
```

Python으로 같은 JSON을 직접 보냈을 때는 HTTP 200이었지만, 운영과 같은 JDK `HttpClient` + Spring
`RestClient` 조합은 422를 재현했다. FastAPI 응답은 요청 body가 XML 문자열이라고 명시했다. 단건
`/embed`와 Batch `/embed/batch` 모두 JSON Content-Type을 명시했고, 단위 테스트에서 두 요청의
`contentType(APPLICATION_JSON)` 호출을 검증했다.

이 결함은 Circuit 상태 전이 자체의 오류는 아니다. Provider가 HTTP 응답을 했으므로 Circuit은 생존한
Provider로 판단하고 정상적으로 CLOSED가 됐다. 그러나 422는 영구 요청 오류라 Job은 재시도 없이
종결됐다. 통합 Drill이 성공 Probe 뒤의 실제 작업 완료까지 검사했기 때문에 이 계약 버그가 드러났다.

## 7. 실행 조건

| 항목 | 값 |
|---|---|
| Host | Darwin arm64 로컬 개발 장비 |
| Spring Backend | 실제 `bootJar`, observer/worker 분리 |
| PostgreSQL | `pgvector/pgvector:0.8.1-pg17` |
| Valkey | `valkey/valkey:9.1-alpine` |
| Prometheus | `prom/prometheus:v3.5.5` |
| Alertmanager | `quay.io/prometheus/alertmanager:v0.33.1` |
| BGE-M3 | 저장소 `backend/embedding-server/Dockerfile` build |
| failure threshold | 3회 |
| Circuit open duration | 30초 |
| Retry Jitter | 0, 테스트 재현성용 |
| 대상 Job max retry | 10회, 실패·성공 Probe를 같은 Job으로 검증하기 위한 테스트 값 |
| 실패 Probe 뒤 Job 지연 | 10분, 경보 `for` 검증용 |
| Prometheus scrape / evaluation | 15초 / 15초, 운영값 |
| Circuit rule `for` | 1분, 운영값 |
| Alertmanager group wait | critical 10초 / warning 30초, 운영값 |
| Alertmanager group interval | 30초, 운영 5분 대신 테스트 시간 제한용 |

실행 명령은 다음과 같다.

```bash
./monitoring/drills/run.sh circuit-recovery \
  --output-dir <결과 디렉터리>
```

## 8. 실행·재시도 기록

| Run ID | 목적·실행 위치 | 명령·방법 | 관측 결과 | 판정과 해석 |
|---|---|---|---|---|
| `issue413-circuit-recovery-r1-20261004` | 최초 전체 Drill | 실제 Stack, Provider pause/unpause | 실패 Probe·경보 억제·성공 Probe 확인 뒤 Job 422 종결 | FAIL. 복구 후 실제 작업 완료 실패 발견 |
| `issue413-provider-contract-r1-20261004` | Provider 계약 격리 | Host Python 요청 | 샌드박스 network 권한에서 요청 전 실패 | 환경 준비 실패, 제품 판정 아님 |
| `issue413-provider-contract-r2-20261004` | Provider 자체 JSON 계약 | Provider 컨테이너 내부 Python 요청 | HTTP 200, 입력 2건·Embedding 2건 | PASS. Provider 계약 정상 |
| `issue413-harness-stop-start-check-r1-20261004` | stop/start Harness 정적 검증 | Python test + Compose render | 단위 3건 성공 뒤 검증 문자열 불일치 | 검증 명령 실패, YAML 표기 확인 후 수정 |
| `issue413-harness-stop-start-check-r2-20261004` | 정적 검증 재시도 | Python test + Compose render | 단위 3건, 고정 port render, diff check 통과 | PASS |
| `issue413-circuit-recovery-r2-20261004` | stop/start 전체 Drill | 실제 Stack | pause를 제거해도 Java Batch 요청 422 | FAIL. Harness가 아닌 Java 계약 결함으로 범위 축소 |
| `issue413-java-provider-contract-r1-20261004` | 운영 Java→Provider 계약 | JDK HttpClient + RestClient + 실제 Provider | HTTP 422, FastAPI가 XML body를 확인 | FAIL. Content-Type 누락 원인 확정 |
| `issue413-embedding-client-unit-r1-20261004` | 수정 후 단위 회귀 | Gradle targeted test | Gradle cache lock 권한에서 실행 전 실패 | 환경 준비 실패, 제품 판정 아님 |
| `issue413-embedding-client-unit-r2-20261004` | 단위 회귀 재시도 | `EmbeddingClientTest` | 전체 성공 | PASS |
| `issue413-java-provider-contract-r2-20261004` | 수정 후 실제 계약 | 운영 EmbeddingClient + 실제 Provider | HTTP 200, model·2개 결과 확인 | PASS |
| `issue413-circuit-recovery-r3-20261004` | JSON 수정 후 전체 Drill | 실제 Stack | Job INDEXED, 경보·복구 전부 통과 | PASS |
| `issue413-webhook-redaction-unit-r1-20261004` | 증거 사전 비식별화 | Python unittest | 기존 3건 + 신규 1건 성공 | PASS |
| `issue413-circuit-recovery-r4-20261004` | 비식별 증거 Run | 실제 Stack | 기능 PASS, 내부 URL 생략 확인 | PASS |
| `issue413-recovered-snapshot-unit-r1-20261004` | 최종 Gauge 증거 보완 | Python unittest | 4건 성공 | PASS |
| `issue413-circuit-recovery-r5-20261004` | 최종 증거 Run | 실제 Stack | 모든 성공 조건과 recovered snapshot 확인 | **PASS** |
| `issue413-backend-regression-r1-20261004` | Backend 전체 회귀 | 격리 PostgreSQL·Valkey + Gradle | 1,315개, 실패·오류 0, skip 2 | PASS. skip은 전용 다중 앱 환경 조건부 기존 테스트 |
| `issue413-monitoring-verify-e2e-r1-20261004` | Monitoring 설정·전달 E2E | `monitoring/verify.sh --e2e` | 규칙·설정·Compose 통과, firing 3초·resolved 2초 | PASS |

각 Run은 목적별 로그를 덮어쓰지 않고 별도로 보존했다. 저장소에는 최종 PASS의 구조화 JSON과
비식별 webhook만 커밋한다. 실패 Run은 원인을 이 문서에 남기되 로컬 경로, 내부 주소, 원문 noisy
로그는 공개 증거에 포함하지 않는다.

## 9. 전체 회귀 검증

### Backend

새 PostgreSQL·Valkey Compose project와 volume을 만들고 동적 loopback port를 Backend 테스트에
주입했다. 캐시 영향을 없애기 위해 `--rerun-tasks`로 실행했다.

```bash
./backend/gradlew -p backend test --no-daemon --rerun-tasks
```

| 항목 | 결과 |
|---|---:|
| JUnit suite | 220개 |
| 테스트 | 1,315개 |
| 성공 | 1,313개 |
| 실패 | 0개 |
| 오류 | 0개 |
| 건너뜀 | 2개 |
| JUnit suite 누적 시간 | 32.570초 |
| Gradle 전체 시간 | 1분 27초 |

건너뛴 두 테스트는 전용 다중 앱 Redis/PostgreSQL 시험 환경이 없으면 `Assumption`으로 중단하도록
설계된 `DashboardCrossNodeSignalRedisIntegrationTest`, `DashboardCrossNodeWebSocketIntegrationTest`다.
테스트 종료 뒤 전용 컨테이너, network, PostgreSQL volume이 모두 삭제됐음을 확인했다.

### Monitoring

```bash
./monitoring/verify.sh --e2e
```

다음을 확인했다.

- Prometheus 설정과 rule file 3개, rule 20개 검증
- Prometheus rule test 3개 통과
- 기본·E2E·Drill·채널 예제를 포함한 Alertmanager 설정 9개 검증
- Python AST, Shell 문법, Compose 렌더링 검증
- 실제 Prometheus → Alertmanager → webhook firing 3초, resolved 2초
- E2E 종료 뒤 컨테이너 0개

## 10. 해석 한계

- 수치는 단일 Darwin arm64 로컬 장비의 loopback 결과다. 운영 SLA가 아니라 같은 조건의 회귀 기준이다.
- Retry Jitter를 0으로 고정했으므로 운영의 분산 폭을 측정하지 않았다. 이 Drill은 Circuit 상태와 경보
  시간 경계를 재현하는 데 목적이 있다.
- 대상 Job의 `max_retry_count=10`, 실패 Probe 뒤 10분 지연은 같은 Job으로 실패·성공 Probe와 1분
  경보를 모두 검증하기 위한 테스트 조작이다. 애플리케이션 기본값을 바꾸지 않는다.
- Alertmanager `group_interval`은 테스트에서 30초다. 운영 5분의 resolved 최악 시간을 이 결과의
  55.749초로 일반화할 수 없다.
- 실제 Slack·Discord 사업자의 응답은 검증하지 않았다. Alertmanager가 HTTP webhook을 보내고 격리
  Receiver가 firing/resolved를 받은 경계까지 검증했다.
- Provider critical 경보가 존재하는 동안 Circuit warning이 억제되는 경로를 검증했다. root-cause가
  없는 독립 Circuit warning 전달은 Prometheus rule test와 Alertmanager routing E2E가 담당한다.

closes #413
