# OpenSQL Outbox 소비 기준선과 문서 데이터 계보: 2026-10-05

관련: [이슈 #439](https://github.com/DocGrid/docgrid/issues/439). 기준 커밋: `750b0887c5bf02fc0fd862eb224577eccb435ae1` (`develop`).

**현재 판정: 로컬 코드·DB 기준선과 GCP primary의 읽기 전용 backlog 집계는 통과, GCP 실동작 소비는 미검증.** 이 문서의 로컬 PostgreSQL 17/pgvector 결과를 GCP OpenSQL 3노드 결과로 바꿔 읽지 않는다. GCP 조회 당시 DB VM은 3대 실행 중이지만 앱 A/B, 공용 캐시, 부하 발생기 VM은 중지 상태였다. Cloud Shell의 암호 전달 실패 뒤 로컬 Cloud SDK·임시 SSH 터널로 같은 DB 세션의 primary·읽기 전용 상태를 확인했고, Outbox에는 `DOCUMENT_VERSION_CREATED / PENDING` **53건**이 있었다. 가장 오래된 건의 `occurred_at`은 **2026-10-02 09:13:11.256192**(시간대 없는 DB 컬럼)이다. 예상 밖의 오래된 backlog가 확인돼 Dispatcher를 켜거나 이벤트를 소비하지 않았다. 실제 배포된 Dispatcher 설정과 backlog 원인은 아직 확인되지 않았다.

## 시험 목적과 안전 관문

기존 Worker의 `INDEXED` 상태만으로는 Outbox가 소비됐다고 할 수 없다. 최초 인덱싱 Job은 업로드 트랜잭션에서 직접 만들어지고, Outbox `DOCUMENT_VERSION_CREATED`와 `DOCUMENT_DELETED`는 별도 Dispatcher 경로를 거친다. 실제 GCP 시험에 앞서 아래를 순서대로 확인한다.

1. 앱 A/B의 실제 배포 리비전과 `SYNC_DISPATCHER_ENABLED` 적용값, DB의 이벤트 유형·상태별 건수와 가장 오래된 `PENDING` 시각을 **읽기 전용**으로 확인한다.
2. 예상 밖의 오래된 `PENDING` 또는 만료되지 않은 `PROCESSING`이 있으면 앱·Dispatcher를 기동하지 않고 발생 경로를 조사한다.
3. 안전 관문 통과 후에만 시험 문서의 새 버전·soft delete 이벤트를 생성하고, 이벤트 상태뿐 아니라 Job/Embedding의 실제 부작용과 중복을 대조한다.
4. 장애 시험은 정상 소비가 확인된 이후의 별도 실행으로 남긴다. 이 문서는 장애 주입 결과가 아니다.

```text
그림 1 — 이미 확인한 코드 경로와 아직 확인하지 못한 GCP 경계

DocumentVersionUploadService / DocumentCommandService
       │ 같은 DB 트랜잭션에서 SyncEventWriter 호출
       ▼
sync_outbox_events: PENDING
       │
       ├─ 로컬: Writer·Claim·Dispatch 단위 테스트 및 DB 원자성 테스트 통과
       │
       └─ GCP: 앱 A/B 중지 → Dispatcher 미실행
                 primary 읽기 전용 집계: PENDING 53건, 가장 오래된 건 10/2
                 → 원인 확인 전 소비 NO-GO
                         ▼
           [backlog 원인 확인 후] PollingScheduler → Handler → PROCESSED + 실제 부작용
```

## 실행별 결과

| 실행 ID · 목적 | 위치·방법 | 관측 결과 | 판정·증거 |
| --- | --- | --- | --- |
| `outbox439-local-01` · Outbox 서비스 단위 기준선 | 로컬 JVM, `./backend/gradlew -p backend test --tests '*SyncEventWriterTest' --tests '*SyncEventClaimServiceTest' --tests '*SyncEventDispatchServiceTest' --tests '*SyncEventFailureServiceTest' --tests '*SyncEventLeaseRecoveryServiceTest' --tests '*DocumentVersionSyncEventHandlerTest' --rerun-tasks --console=plain --no-daemon` | **11/11 통과**, 실패·건너뜀 0 | [실행 기록](evidence/issue-439/outbox439-local-01.md). mock/단위 결과이며 GCP 소비 증거 아님 |
| `outbox439-local-02` · DB 원자성 첫 준비 | 격리된 localhost PostgreSQL 17/pgvector, `SyncTransactionBoundaryIntegrationTest` | DB 준비 경쟁으로 `vector` 확장 생성 실패, Flyway `V32`에서 5개 테스트가 본문 진입 전 실패 | [실패 기록](evidence/issue-439/outbox439-local-02.md). 제품 실패로 분류하지 않음 |
| `outbox439-local-03` · DB 원자성 재실행 | 같은 종류의 격리 DB, SQL 준비 완료를 확인한 뒤 `SyncTransactionBoundaryIntegrationTest` | **5/5 통과**, 실패·건너뜀 0 | [재실행 기록](evidence/issue-439/outbox439-local-03.md). 최초 실패를 덮어쓰지 않음 |
| `outbox439-local-04` · 전체 기본 테스트 | 격리 DB, `./backend/gradlew -p backend test --rerun-tasks --console=plain --no-daemon` | **229 suite / 1,360건: 통과 1,358, 건너뜀 2, 실패 0**. 이 중 Outbox Claim 1건·Dispatch 장애 복구 5건·멱등 동시성 3건·트랜잭션 경계 5건 통과. `BUILD SUCCESSFUL` (1분 17초) | [전체 실행 기록](evidence/issue-439/outbox439-local-04.md). 해당 테스트의 파일 저장소는 대체 객체이므로 GCS 실동작 증거 아님. Gradle 기본 태스크의 제외 태그는 포함되지 않음 |
| `outbox439-gcp-01` · GCP 기동 상태 사전 조회 | Cloud Console VM 목록, 읽기 전용 | DB VM **3/3 실행**, 앱 **0/2 실행**, 공용 캐시 **0/1 실행**, 부하 VM **0/1 실행** | [사전 조회 기록](evidence/issue-439/outbox439-gcp-01.md). DB 게스트·Outbox 행·적용 환경 변수는 미조회 |
| `outbox439-gcp-02` · DB 게스트 역할 및 Outbox 조회 시도 | Cloud Shell에서 한 DB VM에 만료형 인스턴스 SSH 키로 접속, 읽기 전용 Patroni 상태 확인 | SSH 연결 성공. Patroni `/primary` **503**, `/replica` **200**으로 해당 VM은 replica. VM에 `psql`·Python DB 클라이언트가 없어 Outbox SQL은 **실행하지 못함** | [게스트 조회 기록](evidence/issue-439/outbox439-gcp-02.md). 인스턴스 SSH 키·Cloud Shell 개인키 제거 확인. 대기량 미확인으로 **NO-GO** |
| `outbox439-gcp-03` · primary 터널 및 Outbox 집계 접속 시도 | Cloud Shell의 `psql`에서 한 DB VM을 경유해 Patroni primary 후보로 임시 터널 | 암호 요구 단계까지 도달. 로컬 파일 업로드 제한으로 암호 미전송, 집계 SQL **미실행** | [접속 시도 기록](evidence/issue-439/outbox439-gcp-03.md). 터널·임시 키·암호 파일 정리 확인. 대기량 미확인으로 **NO-GO** |
| `outbox439-gcp-04` · primary의 Outbox backlog 실측 | 로컬 JDBC의 읽기 전용 세션, 승인된 DB VM 한 대 경유 임시 터널 | primary·읽기 전용 확인. `DOCUMENT_VERSION_CREATED / PENDING` **53건**, 그 외 그룹 0. 가장 오래된 `occurred_at` **2026-10-02 09:13:11.256192** | [실측 기록](evidence/issue-439/outbox439-gcp-04.md). 집계 성공, 오래된 backlog로 Dispatcher 기동 **NO-GO**. 터널·키 제거 확인 |

로컬 임시 DB 컨테이너는 각 실행 후 제거했고 최종 조회에서도 시험용 컨테이너가 남지 않았다. 로그는 실행 목적별로 분리하고 민감한 경로·인프라 식별자를 기록 전에 가렸다.

## 코드·스키마에서 확인한 데이터 계보

| 데이터 또는 단계 | 확인한 저장·처리 지점 | 삭제 설계에 주는 의미 |
| --- | --- | --- |
| 문서 변경 Event | [`SyncEventWriter`](../../backend/src/main/java/com/opensource/docgrid/domain/sync/service/command/SyncEventWriter.java), [`V36`](../../backend/src/main/resources/db/migration/V36__create_sync_outbox_events.sql) | 현재 Event는 발생 즉시 `PENDING`으로 저장된다. 7일 예약 삭제에는 별도 `available_at` 계약이 필요하다. |
| Dispatcher | [`SyncEventPollingScheduler`](../../backend/src/main/java/com/opensource/docgrid/domain/sync/lifecycle/SyncEventPollingScheduler.java), [`SyncEventDispatchService`](../../backend/src/main/java/com/opensource/docgrid/domain/sync/service/command/SyncEventDispatchService.java) | Claim 후 Handler와 완료 표시는 DB 트랜잭션 안이다. 미래 GCS 삭제 호출을 그대로 Handler 안에 넣으면 DB 트랜잭션이 외부 네트워크를 기다리게 된다. |
| 기존 soft delete Handler | [`DocumentDeletedSyncEventHandler`](../../backend/src/main/java/com/opensource/docgrid/domain/sync/service/handler/DocumentDeletedSyncEventHandler.java) | 현재는 활성 Embedding을 `STALE`로 만들며 GCS 객체·청크·대화 본문을 물리 삭제하지 않는다. |
| 원본·청크·임베딩 | `file_objects`/`document_versions`, `document_chunks.chunk_text`, `embeddings` | 원본 객체 하나만 삭제해도 DB에 본문과 벡터가 남는다. 공유 FileObject 참조를 먼저 판정해야 한다. |
| 검색 결과·인용 | [`SearchResultCommandService`](../../backend/src/main/java/com/opensource/docgrid/domain/search/service/command/SearchResultCommandService.java), [`ResponseCitationCommandService`](../../backend/src/main/java/com/opensource/docgrid/domain/rag/service/command/ResponseCitationCommandService.java) | `matched_text`와 `quoted_text`는 청크 본문의 복사본이다. 청크 FK만 지워서는 복사본을 제거할 수 없다. |
| RAG 프롬프트·답변 | [`PromptBuilder`](../../backend/src/main/java/com/opensource/docgrid/domain/rag/service/PromptBuilder.java), [`RagResponse`](../../backend/src/main/java/com/opensource/docgrid/domain/rag/entity/RagResponse.java) | `prompt_text`와 `answer_text`에도 내용이 남고, 앞선 답변이 이후 대화 프롬프트로 들어갈 수 있다. 출처 추적·삭제 정책이 별도로 필요하다. |

```text
그림 2 — 원본 파일 하나를 지워도 남는 활성 DB 복사본

GCS 원본 PDF ──▶ FileObject ──▶ DocumentVersion
                                  └─ DocumentChunk.chunk_text
                                           ├─ Embedding.vector
                                           ├─ SearchResult.matched_text
                                           └─ ResponseCitation.quoted_text
                               대화 문맥 ──▶ RagResponse.prompt_text / answer_text

이번 작업은 복사본의 존재와 작성 경로를 코드·스키마로 확인했다.
실제 사용자 데이터의 건수나 물리 삭제 결과를 측정한 것은 아니다.
```

## GCP에서 실행한 읽기 전용 쿼리와 중단 조건

아래 SQL은 첫 두 GCP 접속 시도에서는 실행하지 못했고, `outbox439-gcp-04`에서 로컬 JDBC 클라이언트로 실행했다. 같은 세션에서 `pg_is_in_recovery()=false`와 `transaction_read_only=on`을 확인했다. payload, 이벤트 ID, 계정 및 객체 키는 출력하지 않았다. `BEGIN READ ONLY`는 실행 계약을 표현한 것으로, 실제 Java 클라이언트는 `setReadOnly(true)`와 `setAutoCommit(false)`로 읽기 전용 트랜잭션을 시작해 조회 후 `rollback()`했다.

```sql
BEGIN READ ONLY;
SELECT pg_is_in_recovery() AS standby;
SELECT event_type, status, COUNT(*) AS events,
       MIN(occurred_at) AS oldest_occurred_at,
       MIN(available_at) AS earliest_available_at
  FROM sync_outbox_events
 GROUP BY event_type, status
 ORDER BY event_type, status;
COMMIT;
```

실제로 오래된 `PENDING` 53건을 확인했으므로 **NO-GO**다. 최신 primary의 backlog는 측정했지만 앱 A/B의 적용 설정과 개별 이벤트 발생 이유는 확인하지 못했다. GCP 정상 소비, 이벤트 실제 부작용, 장애 후 재처리, GCS 영구 삭제를 완료했다고 주장할 수 없다.
