# #340 RAG 답변 생성 병렬 처리

closes #340

---

## 배경

RAG 답변 생성(`RagJobWorker`)은 GPU 1대·Ollama 인스턴스 1개 전제로 정확히 1개씩 순차 처리하도록
설계되어 있었다(#218/#286/#288). 이 전제 덕분에 지금까지는 락 없는 조건부 UPDATE만으로 안전했지만,
질문이 몰리면 뒤에 온 사용자일수록 대기 시간이 그대로 누적되는 구조적 한계가 있었다.

목표는 GPU를 늘리지 않고, Ollama의 병렬 슬롯(`OLLAMA_NUM_PARALLEL`)이 갖는 여유 용량을 실제로
활용해 애플리케이션 레벨에서 동시에 최대 N개의 질문을 처리하도록 확장하는 것이다.

---

## 설계 — embedding_jobs 패턴을 RAG 규모에 맞게 축소 재사용

이 프로젝트에는 이미 정확히 같은 문제(여러 워커가 안전하게 큐를 나눠 갖는 것)를 해결한 무거운
선례(`embedding_jobs` 워커: `FOR UPDATE SKIP LOCKED` 원자적 claim + 전용 `ThreadPoolExecutor`)가
있어서, 새로 발명하지 않고 그 패턴을 그대로 축소해 재사용했다.

### 1. Claim 표식 — 새 `claimed_at` 컬럼 (V43 마이그레이션)

RAG는 `enqueue()` 시점에 곧바로 `status=PROCESSING`이 된다(`embedding_jobs`처럼 PENDING→PROCESSING의
별도 단계가 없음). 그래서 "대기 중(아직 아무도 안 집음)"과 "지금 실제로 처리 중(누가 이미 집음)"을
구분할 방법이 없었다 — 이 구분이 없으면 짧은 claim 트랜잭션이 커밋된 뒤에도 다른 워커가 같은 행을
또 집을 수 있다.

`updatedAt`(BaseEntity, `@LastModifiedDate`)을 재사용하는 방안은 기각했다 — 완료 확정 경로
(`completeSuccessIfProcessing`/`forceFailIfProcessing`)가 전부 `@Modifying` 벌크 JPQL UPDATE라
JPA 생명주기(`@PreUpdate`)를 안 거쳐서 `updatedAt`이 절대 안 채워짐을 직접 확인했다.

```sql
-- V43__add_rag_responses_claimed_at.sql
ALTER TABLE rag_responses ADD COLUMN claimed_at TIMESTAMP;
```

### 2. 동시성 상한 — 전용 `ThreadPoolExecutor` + `Semaphore`

`domain/worker/config/WorkerExecutionConfig.java`(이 코드베이스 유일한 커스텀 스레드풀 선례)를
그대로 본떴다: `core=max=N`인 `ThreadPoolExecutor` + `SynchronousQueue`(큐잉 없음) +
`AbortPolicy`(꽉 차면 즉시 거부) + `CustomizableThreadFactory`.

동시성 예약은 `embedding_jobs`의 `WorkerExecutionSlotPool`(전체 클래스, 종료 플래그 +
introspection 메서드 포함)까지는 필요 없다고 판단해 순수 `Semaphore(N, true)`만 썼다 — RAG는
별도 워커 등록/우아한 종료 조율이나 대시보드 노출 요구가 없어서다. **핵심 안전장치는 "로컬 슬롯을
먼저 확보한 뒤에만 DB claim을 시도"하는 순서** — 이 순서 덕분에 "claim은 됐는데 실행할 스레드가
없는" 유령 job이 생기지 않는다(`embedding_jobs`의 `WorkerJobPollingScheduler`도 동일한 순서).

---

## 신규/변경 파일

| 파일 | 변경 |
|---|---|
| `db/migration/V43__add_rag_responses_claimed_at.sql` | 신규. `claimed_at TIMESTAMP` nullable 컬럼 추가만. |
| `domain/rag/entity/RagResponse.java` | `claimedAt` 필드 + `markClaimed(LocalDateTime)` 메서드 추가. 짧은 claim 트랜잭션 안에서만 로드·수정·커밋되므로 #218 detached-entity 버그와 다른 안전한 케이스. |
| `domain/rag/repository/RagResponseRepository.java` | `findFirstByStatusOrderByCreatedAtAsc` 제거(참조 없음 확인). `findNextUnclaimedProcessingForUpdate()`(native, `SKIP LOCKED`) 신규. `findWithQueryAndUserById(Long)`(`@EntityGraph`) 신규 — 기존 `findById`는 그대로 둠. `releaseAllClaimsOnStartup()`(재시작 복구용, CodeRabbit 리뷰 반영) 신규. |
| `domain/rag/service/command/RagResponseClaimService.java` | 신규. `@Service @Transactional`, `claimNext(): Optional<Long>` — 짧은 트랜잭션 안에서 claim 쿼리 실행 후 즉시 `markClaimed()` 호출, 커밋과 함께 락 해제. `recoverStaleClaimsOnStartup()`(CodeRabbit 리뷰 반영) 추가. |
| `domain/rag/config/RagExecutionConfig.java` | 신규. `ragWorkerJobExecutor`(`ThreadPoolExecutor`) + `ragWorkerSlots`(`Semaphore`), `rag.worker.max-concurrency` 기반. `@ConditionalOnProperty` 없음(RAG는 항상 켜져야 함). |
| `domain/rag/service/RagJobWorker.java` | `processNext()`를 디스패처로 재작성: `while (ragWorkerSlots.tryAcquire())` → claim → 비었으면 슬롯 반환, 있으면 `ragWorkerJobExecutor.execute(() -> executeClaimedJob(jobId))`. `recoverStaleClaimsOnStartup()`(`@EventListener(ApplicationReadyEvent.class)`, CodeRabbit 리뷰 반영) 추가. |
| `application.yml` | `rag.worker.max-concurrency: 2` 추가. |
| `RagFacade.java` | 진단용 로그 한 줄 추가(`promptTokens`/`answerTokens`, 아래 "추가 개선 검토" 참고). Javadoc 한 줄 수정(옛 메서드명 참조 정정). 로직 무변경. |

**변경 불필요 확인됨**: `RagJobTimeoutSweeper`, `RagResponseCommandService` — 전부 job id 기반 +
조건부 UPDATE라 호출자가 몇 명이든 이미 안전.

---

## 테스트

- 기존 4개 테스트 파일을 새 구조(claim 서비스, `Semaphore`, `ThreadPoolExecutor`)에 맞게 갱신
- 신규 `RagResponseClaimIntegrationTest` 추가 — `EmbeddingJobClaimIntegrationTest`를 그대로 본떠
  실제 동시 트랜잭션(`TransactionTemplate` + `PROPAGATION_REQUIRES_NEW`, 별도 스레드)으로
  `SKIP LOCKED` 스킵 동작과 "두 스레드가 동시에 claim해도 하나만 성공"을 검증
- `RagJobWorkerIntegrationTest`는 반드시 수정이 필요했다 — `processNext()`가 이제 claim만 하고
  즉시 반환하므로(실제 처리는 Executor로 위임), 기존의 "호출 직후 동기 검증" 방식이 깨져서
  Awaitility로 전환
- `RagJobWorkerConcurrentQueueIntegrationTest`에 동시성 증명 assertion 추가 — `claimed_at` 값들의
  최소 간격이 10초 이내인지 확인(실제 동시 claim의 증거)
- (CodeRabbit 리뷰 반영) `RagResponseRepositoryTest`에 `releaseAllClaimsOnStartup()` 케이스 2개,
  `RagJobWorkerTest`에 `recoverStaleClaimsOnStartup()` 위임 검증 1개 추가

### 실행 결과

```
RAG 패키지 테스트 9개 파일 전부 통과 (통합 테스트 3개 포함, 실제 로컬 PostgreSQL + Ollama 대상)
전체 프로젝트 테스트 → 실패 0건, 에러 0건 (초기 확인 1,178개 + CodeRabbit 반영 후 재확인)
```

---

## 실측 — 3단계로 진행

### 1단계: Ollama 서버 자체의 병렬 처리 능력 확인

`~/Library/LaunchAgents/homebrew.mxcl.ollama.plist`에 `OLLAMA_NUM_PARALLEL`을 설정하고
`launchctl unload`/`load`로 재로드(`brew services restart`는 plist를 재생성해 커스텀 설정을
지우므로 쓰지 않음 — #210에서 이미 확인된 함정).

curl로 동일 프롬프트를 동시에 N개 날려서 실측:

| N | 총 처리 토큰 | 전체 소요시간 | 처리량(토큰/초) | 개별 요청당 속도 |
|---|---|---|---|---|
| 1 (단독) | 73 | 4.92초 | 14.85 | 14.85 |
| 2 | 164 | 8.68초 | 18.9 | ~10.0 |
| 3 | 228 | 12.28초 | 18.6 | ~6.4 |
| 4 | 297 | 13.24초 | 22.4 | ~5.7 |

**결론**: 1→2에서 확실한 이득(약 27% 처리량 증가)이 나오고, 2 이후로는 처리량은 제자리인데
개별 응답 속도만 계속 나빠짐. **N=2가 이 하드웨어(맥 통합메모리)의 실질적 적정선**이라고
결론짓고 최종적으로 `OLLAMA_NUM_PARALLEL=2`로 확정.

### 2단계: 실제 RAG 파이프라인으로 동시성 재검증

`RagJobWorkerConcurrentQueueIntegrationTest`(질문 3개 동시 접수)를 N=2 설정에서 재실행:

```
개별 질문 소요시간: 4.1초 / 26.1초 / 38.8초
claim 시각: 2개는 거의 동시(18ms 차이), 3번째는 슬롯이 빌 때까지 약 27초 대기
전체 소요시간: 41.2초
```

순차 처리였다면 4.1+26.1+38.8=69초가 걸렸어야 하는데 실제로는 41.2초 — **약 40% 단축**을
실측으로 확인.

### 3단계: 타임아웃 값(`generate-deadline`/`stale-threshold`) 재검토

실측 최악값(38.8초)이 현재 `generate-deadline`(60초)/`stale-threshold`(90초) 안에 여유 있게
들어옴. `#218` 설계 당시 정책("실사용에서 타임아웃 로그가 쌓이면 그때 재검토")을 뒤집을 근거를
찾지 못해 **현재 값 유지로 결론**.

---

## 추가 개선 검토 (외부 피드백 기반, 실측으로 검증)

병렬화 이후 추가로 시도할 수 있는 개선안들을 검토하고, 가능한 건 직접 실측했다.

### 진단 로그 추가 (적용함)

`RagFacade.java`의 완료 로그에 `promptTokens`/`answerTokens`를 추가 — 느린 job이 프롬프트를
읽느라(prefill) 오래 걸렸는지 답변을 쓰느라(decode) 오래 걸렸는지 로그만으로 구분 가능하게 함.

```java
log.info("[RAG] done queryId={} responseId={} latencyMs={} promptTokens={} answerTokens={}",
    queryId, job.getId(), result.latencyMs(), result.inputTokenCount(), result.outputTokenCount());
```

### 실제 서버 기동 + 실제 QA로 진단 데이터 확보

로컬에 백엔드를 직접 기동(`./backend/gradlew -p backend bootRun --args='--spring.profiles.active=local'`)
하고, 회원가입 → 로그인 → `POST /search`까지 실제 API 흐름으로 QA 진행. 실제 인덱싱된 문서
("Spring Boot 실무 가이드")에 대한 질문으로 진짜 RAG 응답을 받아 진단 로그 확인:

```
[RAG] done queryId=223 responseId=131 latencyMs=35922 promptTokens=1503 answerTokens=400
```

`answerTokens`가 상한(400)에 정확히 걸림 — 답변 생성(decode)이 병목일 가능성을 시사.

### `num-predict` 낮추기 실측 (효과 없다고 결론)

같은 실제 프롬프트(DB에서 그대로 가져옴)로 `num_predict`만 바꿔가며 직접 재현:

| num_predict | 소요시간 | 실제 생성 토큰 | 잘렸는지 | 답변 완성도 |
|---|---|---|---|---|
| 150 | 9.5초 | 150 | 예(length) | 5~6개 주제 중 2개도 못 채움 |
| 250 | 15.0초 | 250 | 예(length) | 자동설정까지만, 내장서버/테스트/배포 누락 |
| 400(현재) | 21.6초 | 352(자연종료) | 아니오(stop) | 전체 주제 다 다루고 정상 마무리 |

**결론**: `num_predict`를 낮추면 확실히 빨라지지만, 그만큼 답변이 요청 내용을 다 못 채우고
끊긴다. "핵심 내용을 자세히 설명해줘" 같은 질문에는 지금 400이 오히려 적정값에 가까웠다 —
**이 레버는 기각**. (안 해봤으면 몰랐을 유의미한 음성 결과.)

### 검토했으나 보류한 것들

| 후보 | 보류 이유 |
|---|---|
| 토큰 스트리밍 | 체감 지연 개선 효과는 크지만, 이번 스코프에서 제외하기로 결정(사용자 판단) |
| 큐 공정성(사용자당 동시 처리 1건 제한) | 효과는 있으나 이번엔 스킵하기로 결정 |
| `OLLAMA_KV_CACHE_TYPE=q8_0` 재검토 | #210에서 글자 깨짐 원인으로 지목돼 제거됐던 설정이 현재 다시 켜져 있는 게 확인됨(원인 미상, 실제로 한자 혼입 재현도 확인함). 코드가 이미 방어하고 있어 당장 급하지 않다고 판단해 보류. |
| 답변 캐시 | 멀티턴 문맥이 프롬프트에 섞여 들어가는 구조라 캐시 히트율이 낮을 것으로 예상, 우선순위 낮춤 |
| GPU 인프라 교체(클라우드 GPU/vLLM) | 진짜 처리량 상한을 뚫는 유일한 방법이지만, 비용·복잡도가 이번 스코프보다 큼. 코드 변경은 `ollama.server.base-url` 하나만 바꾸면 되는 수준이라 향후 확장 경로로 남겨둠 |
| Speculative decoding | Ollama 0.32.13 CLI/API에 관련 옵션이 전혀 없음을 확인 — 현재 버전에서는 지원 안 함으로 결론 |
| Kafka 도입 | 병목이 메시지 전달 속도가 아니라 GPU 메모리 대역폭이라 무관 — 오히려 불필요한 인프라 복잡도만 추가. 기각. |

---

## 코드리뷰 반영 (CodeRabbit)

PR #341에 직접 고도화 아이디어를 질문했고, 5가지 제안이 왔다. 하나씩 실제 코드/과거 설계
문서와 대조 검증한 뒤 처리했다.

| # | 제안 요지 | 처리 | 근거 |
|---|---|---|---|
| 1 | 재시작 시 `claimed_at`이 남은 job은 새 프로세스가 영원히 재claim하지 못한다 | **반영함** | 검증 결과 실제 퇴보였다 — #218 이전(순수 status 기반) 방식은 재시작하면 자동으로 재시도됐는데, claim 도입 후에는 스위퍼의 fallback만 기다리게 된다. 앱 시작 시 1회(`ApplicationReadyEvent`) `claimed_at`을 전부 풀어주는 복구를 추가했다("인스턴스 1개" 전제 위에서만 안전, 이 전제는 이미 이 Worker 전체 설계의 기존 전제와 동일). |
| 2 | 큐 대기 시간과 실행 시간을 분리해 타임아웃 판단해야 한다 | **반영 안 함(문서화만)** | `#286` 설계 문서에 이미 동일한 내용이 "실사용에서 재조정" 항목으로 기록돼 있었다. 오늘 실측 최악값(38.8초)이 90초 기준 안에 여유 있어 지금 분리할 근거 데이터가 없다 — 감으로 값을 새로 짓느니, 값을 넉넉히 잡으면 진짜 hang 감지가 오히려 늦어지는 트레이드오프도 있어 보류. |
| 3 | claim 쿼리에 partial index 추가 검토 | **반영 안 함** | CodeRabbit 스스로도 "큐가 작으면 우선순위 낮음, `EXPLAIN`으로 확인 후 결정"이라고 명시. 설계 문서에 이미 같은 결론(인덱스 불필요)이 적혀 있었다. |
| 4 | claim 타이밍만이 아니라 실제 `generate()` 호출 자체가 겹치는지 직접 증명하는 테스트 추가 | **반영 안 함(후속 과제)** | claim 직후 Executor가 곧바로 처리를 시작하는 구조상 claim 타이밍이 곧 generate 호출 타이밍의 신뢰할 만한 대리 지표다. "정렬된 리스트의 인덱스 0,1이 항상 최소 간격"이라는 지적도 일반적으로는 맞지만, 이 설계(슬롯 2개+3건 접수)에서는 3번째 job이 항상 앞 두 개보다 늦게 claim될 수밖에 없어 현재 검증 방식이 틀리진 않았다. 더 강한 증명(`OllamaClient` 테스트 더블 + `CountDownLatch`)은 가치 있으나 지금 급하지 않음. |
| 5 | queue_wait/execution_time/슬롯 사용률 등 운영 지표 추가 | **반영 안 함(다른 계획에 포함)** | 이미 별도로 미뤄둔 관측성(Grafana/트레이싱) 작업 범위와 겹친다 — 그때 같이 반영 예정. |

## 설계 결정 요약

- **claim 표식은 새 컬럼(`claimed_at`)으로**: `updatedAt` 재사용은 벌크 UPDATE 경로에서 실제로
  안 채워짐을 코드로 확인하고 기각
- **`embedding_jobs`의 무거운 패턴을 축소 재사용**: `WorkerExecutionSlotPool` 클래스 전체 대신
  순수 `Semaphore`로 단순화 — RAG는 분산 워커 등록/우아한 종료 요구가 없어서
- **로컬 슬롯 확보 → DB claim 순서 고정**: "claim은 됐는데 실행할 스레드가 없는" 유령 job 방지가
  이 순서의 핵심 목적
- **`RagFacade`/`RagJobTimeoutSweeper` 무변경**: #288에서 이미 "누가 몇 명이든 안전"하게
  설계돼 있어서 그 위에 얹기만 함
- **N=2를 이론이 아니라 실측으로 확정**: curl 기반 인프라 실측 + 실제 파이프라인 재검증 2단계로
  교차 확인
- **속도 개선 후속 검토는 실측 우선**: "효과 있을 것 같다"는 감이 아니라 직접 서버를 켜고 실제
  QA로 데이터를 뽑아서 기각/채택을 결정(`num-predict` 축소가 대표적 — 실측 전엔 유망해 보였으나
  품질 손실이 이득보다 크다는 게 실측으로 드러남)

---

## 남은 이슈 / TODO

- `OLLAMA_KV_CACHE_TYPE=q8_0`이 왜 다시 켜졌는지 원인 불명 — 별도 조사 필요
- 큐 공정성(사용자당 동시 처리 제한)은 구현 난이도 대비 효과가 괜찮아 보이는 후보로, 나중에
  재검토 가치 있음
- GPU 인프라 확장(클라우드 GPU/vLLM)은 코드 준비(claim 구조)는 되어 있으나 라우팅 로직 없음 —
  실제로 여러 Ollama 인스턴스를 쓰게 되면 별도 설계 필요
- 진단 로그(`promptTokens`/`answerTokens`)는 이제 막 추가되어 실사용 데이터가 아직 없음 — 운영
  중 쌓이는 로그를 보고 추가 튜닝 여부 재판단
