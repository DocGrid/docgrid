# OpenSQL Outbox 소비 기준선과 문서 데이터 계보: 2026-10-05

관련: [이슈 #439](https://github.com/DocGrid/docgrid/issues/439), [PR #440](https://github.com/DocGrid/docgrid/pull/440). 최초 분석 기준 커밋: `750b0887c5bf02fc0fd862eb224577eccb435ae1` (`develop`). 격리 시험 코드는 이 PR의 `OpenSqlIsolatedOutboxDispatcherIntegrationTest`에 있다.

**현재 판정: GCP OpenSQL primary에 새로 만든 격리 DB에서 실제 Dispatcher 코드의 정상 소비·실패 후 재처리 통과. 기존 `docgrid` DB의 53건과 연결 Job은 보존.** 로컬 JVM을 임시 SSH 터널로 GCP DB에 연결한 결과이지, 중지된 앱 A/B VM의 Dispatcher가 동작했다는 뜻은 아니다. 원래 DB에는 `DOCUMENT_VERSION_CREATED / PENDING` **53건**이 있었고 모두 Embedding Job의 외래키 참조 대상이었다. 연결 Job은 `INDEXED` 34건·`FAILED` 19건이므로 이벤트 단독 삭제를 하지 않았다. 사용자 선택에 따라 별도 시험 DB에 Flyway **46건**을 적용하고 테스트 전용 이벤트만 생성했다. 최종 전용 태스크 실행 `outbox439-isolated-07`은 **2/2 통과**했고 Event **2건 모두 PROCESSED**, 성공 Attempt **2건**, 의도한 실패 Attempt **1건**, 출처 연결 Job **1건**을 별도 SQL로 대조했다. 앞선 반복 실행 `-02`∼`-06`의 누적 상태도 기록했다. 실제 배포된 Dispatcher 설정과 원래 backlog의 발생 이유는 여전히 미확인이다.

## 시험 목적과 안전 관문

기존 Worker의 `INDEXED` 상태만으로는 Outbox가 소비됐다고 할 수 없다. 최초 인덱싱 Job은 업로드 트랜잭션에서 직접 만들어지고, Outbox `DOCUMENT_VERSION_CREATED`와 `DOCUMENT_DELETED`는 별도 Dispatcher 경로를 거친다. 원래 DB에서는 아래 1·2번 안전 관문을 적용했고, backlog가 있으므로 3·4번을 원래 앱·DB에서 실행하지 않았다. 대신 격리 DB에서 한정된 정상 소비와 결정적 재시도를 실행했다.

1. 앱 A/B의 실제 배포 리비전과 `SYNC_DISPATCHER_ENABLED` 적용값, DB의 이벤트 유형·상태별 건수와 가장 오래된 `PENDING` 시각을 **읽기 전용**으로 확인한다.
2. 예상 밖의 오래된 `PENDING` 또는 만료되지 않은 `PROCESSING`이 있으면 앱·Dispatcher를 기동하지 않고 발생 경로를 조사한다.
3. 안전 관문 통과 후에만 원래 앱에서 시험 문서의 새 버전·soft delete 이벤트를 생성하고 Job/Embedding의 실제 부작용과 중복을 대조한다. 이 단계는 원래 DB에서 **미실행**이다.
4. 장애 시험은 원래 앱의 정상 소비가 확인된 이후의 별도 실행으로 남긴다. 이 문서는 장애 주입 결과가 아니다.

```text
그림 1 — 원래 DB를 보존하면서 실제 Dispatcher 코드만 분리해 검증한 경계

DocumentVersionUploadService / DocumentCommandService
       │ 같은 DB 트랜잭션에서 SyncEventWriter 호출
       ▼
sync_outbox_events: PENDING
       │
       ├─ 원래 GCP DB: PENDING 53건, 모든 Event를 Job이 참조
       │                앱 A/B 중지, Dispatcher 미실행, 기존 데이터 변경 0건
       │
       └─ 새 격리 GCP DB: 로컬 테스트 JVM이 임시 터널로 접속
                         실제 PollingScheduler.poll() → Claim → Handler
                         → PROCESSED + Attempt + Job 출처를 DB에서 대조

남은 경계: GCP 앱 A/B에 배포된 Dispatcher 설정·OpenProxy 경유·GCS 실파일·장애 수렴.
```

## 실행별 결과

| 실행 ID · 목적 | 위치·방법 | 관측 결과 | 판정·증거 |
| --- | --- | --- | --- |
| `outbox439-local-01` · Outbox 서비스 단위 기준선 | 로컬 JVM, `./backend/gradlew -p backend test --tests '*SyncEventWriterTest' --tests '*SyncEventClaimServiceTest' --tests '*SyncEventDispatchServiceTest' --tests '*SyncEventFailureServiceTest' --tests '*SyncEventLeaseRecoveryServiceTest' --tests '*DocumentVersionSyncEventHandlerTest' --rerun-tasks --console=plain --no-daemon` | **11/11 통과**, 실패·건너뜀 0 | [실행 기록](evidence/issue-439/outbox439-local-01.md). mock/단위 결과이며 GCP 소비 증거 아님 |
| `outbox439-local-02` · DB 원자성 첫 준비 | 격리된 localhost PostgreSQL 17/pgvector, `SyncTransactionBoundaryIntegrationTest` | DB 준비 경쟁으로 `vector` 확장 생성 실패, Flyway `V32`에서 5개 테스트가 본문 진입 전 실패 | [실패 기록](evidence/issue-439/outbox439-local-02.md). 제품 실패로 분류하지 않음 |
| `outbox439-local-03` · DB 원자성 재실행 | 같은 종류의 격리 DB, SQL 준비 완료를 확인한 뒤 `SyncTransactionBoundaryIntegrationTest` | **5/5 통과**, 실패·건너뜀 0 | [재실행 기록](evidence/issue-439/outbox439-local-03.md). 최초 실패를 덮어쓰지 않음 |
| `outbox439-local-04` · 전체 기본 테스트 | 격리 DB, `./backend/gradlew -p backend test --rerun-tasks --console=plain --no-daemon` | **229 suite / 1,360건: 통과 1,358, 건너뜀 2, 실패 0**. 이 중 Outbox Claim 1건·Dispatch 장애 복구 5건·멱등 동시성 3건·트랜잭션 경계 5건 통과. `BUILD SUCCESSFUL` (1분 17초) | [전체 실행 기록](evidence/issue-439/outbox439-local-04.md). 해당 테스트의 파일 저장소는 대체 객체이므로 GCS 실동작 증거 아님. Gradle 기본 태스크의 제외 태그는 포함되지 않음 |
| `outbox439-local-05` · 기본 회귀 첫 재실행 | 로컬 기본 테스트 DB 포트 준비 없이 `gradle test` 시작 | 테스트 기본 포트 **55432**와 실행 중 개발 DB 포트 **55433** 불일치로 접속 실패 연쇄, 중단 코드 **130** | [환경 실패 기록](evidence/issue-439/outbox439-local-05.md). 코드 결함으로 분류하지 않음 |
| `outbox439-local-06` · 분리 DB 전체 회귀 | 새 일회용 PostgreSQL/pgvector, 전체 `gradle test` | **1,360건: 통과 1,356, 건너뜀 2, 실패 2**. 기존 인덱싱·RAG 동시성 시험에서 실패 | [실패 기록](evidence/issue-439/outbox439-local-06.md). 원인 미확정·별도 재실행 |
| `outbox439-local-07` · 실패 클래스 독립 재실행 | 다시 새 일회용 DB에서 해당 두 클래스만 실행 | 인덱싱 **9/9**, RAG Claim **2/2**, 합계 **11/11 통과** | [재실행 기록](evidence/issue-439/outbox439-local-07.md). 앞선 실패를 삭제하거나 원인 확정하지 않음 |
| `outbox439-local-08` · 최종 기본 회귀 | 또 다른 새 일회용 DB에서 전체 `gradle test` | **229 suite / 1,360건: 통과 1,358, 건너뜀 2, 실패 0**, `BUILD SUCCESSFUL` | [최종 회귀 기록](evidence/issue-439/outbox439-local-08.md). 클라우드 전용 태그는 기본 실행에서 제외 |
| `outbox439-gcp-01` · GCP 기동 상태 사전 조회 | Cloud Console VM 목록, 읽기 전용 | DB VM **3/3 실행**, 앱 **0/2 실행**, 공용 캐시 **0/1 실행**, 부하 VM **0/1 실행** | [사전 조회 기록](evidence/issue-439/outbox439-gcp-01.md). DB 게스트·Outbox 행·적용 환경 변수는 미조회 |
| `outbox439-gcp-02` · DB 게스트 역할 및 Outbox 조회 시도 | Cloud Shell에서 한 DB VM에 만료형 인스턴스 SSH 키로 접속, 읽기 전용 Patroni 상태 확인 | SSH 연결 성공. Patroni `/primary` **503**, `/replica` **200**으로 해당 VM은 replica. VM에 `psql`·Python DB 클라이언트가 없어 Outbox SQL은 **실행하지 못함** | [게스트 조회 기록](evidence/issue-439/outbox439-gcp-02.md). 인스턴스 SSH 키·Cloud Shell 개인키 제거 확인. 대기량 미확인으로 **NO-GO** |
| `outbox439-gcp-03` · primary 터널 및 Outbox 집계 접속 시도 | Cloud Shell의 `psql`에서 한 DB VM을 경유해 Patroni primary 후보로 임시 터널 | 암호 요구 단계까지 도달. 로컬 파일 업로드 제한으로 암호 미전송, 집계 SQL **미실행** | [접속 시도 기록](evidence/issue-439/outbox439-gcp-03.md). 터널·임시 키·암호 파일 정리 확인. 대기량 미확인으로 **NO-GO** |
| `outbox439-gcp-04` · primary의 Outbox backlog 실측 | 로컬 JDBC의 읽기 전용 세션, 승인된 DB VM 한 대 경유 임시 터널 | primary·읽기 전용 확인. `DOCUMENT_VERSION_CREATED / PENDING` **53건**, 그 외 그룹 0. 가장 오래된 `occurred_at` **2026-10-02 09:13:11.256192** | [실측 기록](evidence/issue-439/outbox439-gcp-04.md). 집계 성공, 오래된 backlog로 Dispatcher 기동 **NO-GO**. 터널·키 제거 확인 |
| `outbox439-gcp-05` · 삭제 전 외래키 안전 관문 | 로컬 JDBC의 primary 읽기 전용 세션에서 이벤트·Job·실행 이력의 참조 수 집계 | 전체 이벤트 **53건** 모두 Job이 참조. 연결 Job `INDEXED` **34**, `FAILED` **19**. DB 변경 **0건** | [참조 확인 기록](evidence/issue-439/outbox439-gcp-05.md). 이벤트 단독 삭제 불가. 앱·Dispatcher 기동 **NO-GO**. 터널·키 제거 확인 |
| `outbox439-isolated-01` · 격리 DB 첫 기동 | 로컬 JVM → GCP 격리 DB, Flyway + Dispatcher 테스트 | `JWT_SECRET` 누락으로 Spring Context 시작 실패. 격리 DB Flyway **46건**, Event **0건** | [실패 기록](evidence/issue-439/outbox439-isolated-01.md). 제품 결함 아님 |
| `outbox439-isolated-02` · 정상 소비 | 로컬 JVM에서 실제 Scheduler 한 주기를 실행, 별도 DB 대조 | **1/1 통과**. Event `PROCESSED` 1·성공 Attempt 1·연결 Job 1 | [정상 소비 기록](evidence/issue-439/outbox439-isolated-02.md). GCP DB 실동작이지만 GCP 앱 VM 실동작은 아님 |
| `outbox439-isolated-03` · Retry 첫 시도 | Handler 오류 뒤 시험 Event payload 복구·재Polling | 실패 Attempt 1·`PENDING` 예약 확인. 두 번째 Claim은 실패, **0/1 통과**. DB/JVM 시각 차이 약 **3초** | [시각 경계 실패 기록](evidence/issue-439/outbox439-isolated-03.md). 시험의 DB 시각 사용 오류; 실패 시험 Event 1건만 격리 DB에서 정리 |
| `outbox439-isolated-04` · Retry 수정 후 | 앱 `Clock` 기준으로 시험 Event의 `available_at`을 조정해 재실행 | **1/1 통과**. 누적 Event 완료 2·성공 Attempt 2·실패 Attempt 1 | [재처리 기록](evidence/issue-439/outbox439-isolated-04.md). 두 Attempt는 동일 Event의 실패 후 성공 |
| `outbox439-isolated-05` · 두 경로 결합 | 정상 소비·Retry를 한 클래스에서 실행 | **2/2 통과**. 누적 Event 완료 4·성공 Attempt 4·실패 Attempt 2 | [결합 회귀 기록](evidence/issue-439/outbox439-isolated-05.md). 최종 공개 코드 수정 전 실행 |
| `outbox439-isolated-06` · 최종 코드 | 개인 식별자 없는 seed ID 조회로 바꾼 후 전체 클래스 재실행 | **2/2 통과**. 누적 Event 완료 6·성공 Attempt 6·실패 Attempt 3·연결 Job 3. 원래 DB `PENDING` 53 유지 | [최종 실행 기록](evidence/issue-439/outbox439-isolated-06.md). 실제 GCP DB에 대한 제한된 Dispatcher 증거 |
| `outbox439-isolated-guard-01` · 기본 테스트 분리 | 전용 태스크를 격리 DB 환경 변수 없이 실행 | 예상대로 태스크 사전 가드에서 중단, 테스트 JVM·DB 접근 0건 | [가드 기록](evidence/issue-439/outbox439-isolated-guard-01.md). 첫 로컬 권한 오류와 태스크 자체의 정상 거부를 구분 |
| `outbox439-isolated-07` · 전용 태스크 최종 | 새 격리 DB에서 `openSqlOutboxIsolatedTest` 실행 | **2/2 통과**. Event 완료 2·성공 Attempt 2·실패 Attempt 1·연결 Job 1. 원래 DB Event/Job 53/53 | [전용 태스크 기록](evidence/issue-439/outbox439-isolated-07.md). 약 11MB 시험 DB 재삭제·터널 종료 |

기존 로컬 임시 DB 컨테이너는 각 실행 후 제거했다. 격리 OpenSQL `-01`∼`-06`은 같은 신규 시험 DB에서 반복해 건수가 누적됐으며, 전용 태스크 `-07`은 재생성한 별도 DB에서 실행했다. 각각 최종 대조 후 **약 11MB 시험 DB를 삭제**했고, 원래 DB의 `PENDING` Event **53건**과 그 출처를 참조하는 Job **53건**은 그대로였다. 터널·임시 파일도 제거했고 VM의 임시 인스턴스 SSH 키 항목은 없었다. [원복 기록](evidence/issue-439/outbox439-isolated-cleanup.md). Gradle이 만든 원본 XML에는 로컬 호스트 정보가 포함될 수 있으므로 저장소에 커밋하지 않고, 비식별 종료 결과와 읽기 전용 DB 재조회만 기록했다. 로컬 콘솔을 실시간으로 관찰했으나 비식별된 원문 스트림을 별도 파일로 수집한 것은 아니다.

## 격리 시험의 실제 실행 경계

| 경계 | 이번에 한 일 | 하지 않은 일 |
| --- | --- | --- |
| DB | 현재 primary에서 원래 DB와 다른 시험 DB 생성, `vector` 확장, migration/app 계정 분리, Flyway **46건** 적용 | 원래 DB의 Event·Job 수정 또는 삭제 |
| 앱 | 로컬 Java 17 테스트 JVM에서 실제 `SyncEventPollingScheduler.poll()` → Claim → Handler → 성공/실패 Attempt 경로 실행 | 중지된 GCP 앱 A/B VM에 배포하거나 Dispatcher 설정을 변경 |
| 접속 | 임시 루프백 SSH 터널로 primary에 직접 JDBC 접속 | OpenProxy A/B 라우팅·장애 전환 실측 |
| 파일 | 업로드가 DB에 Event와 Job을 함께 쓰는 경로 확인, 파일 저장 호출만 테스트 대체 | GCS 객체 실제 업로드·삭제 |
| 원복 | 격리 DB와 임시 접속만 제거, 원래 Event·Job **53/53** 보존 | 원래 backlog 발생 원인의 확정 또는 운영 큐 소비 |

```text
그림 3 — 격리 시험에서 실제로 통과한 정상·실패 경로

로컬 시험 JVM ── DocumentUploadFacade.upload() ──▶ 격리 DB
       │              ├─ DocumentVersion INSERT
       │              ├─ Outbox Event PENDING INSERT
       │              └─ Embedding Job(source_event_id) INSERT
       │
       └─ SyncEventPollingScheduler.poll()
              ├─ Claim Service: PENDING → PROCESSING + Attempt 시작
              ├─ 정상: Handler가 이미 연결된 Job을 확인 → 중복 생성 없음
              │        └─ Event PROCESSED + Attempt SUCCEEDED
              └─ 시험 오류: 잘못된 payload → Handler Rollback
                       → Failure Service: Attempt FAILED + Retry 예약
                       → 시험 Event payload 복구·앱 Clock 기준 시각 지정
                       → 다음 poll: Event PROCESSED + Attempt SUCCEEDED

원래 docgrid DB: PENDING 53건·연결 Job 53건을 끝까지 보존.
```

재현할 때는 `OUTBOX_ISOLATED_DB_NAME`에 `docgrid_outbox_439_YYYYMMDD` 형식의 **새 DB**만 지정한다. 기본 `test`는 이 클라우드 전용 태그를 제외하고, 전용 태스크의 `doFirst`와 테스트의 `@DynamicPropertySource`가 대상·필수 환경 변수를 JVM 기동 전후 두 경계에서 검사한다. 먼저 현재 primary·이름 충돌·기존 두 계정·`vector` 확장을 확인하고, 새 DB의 소유자를 migration 계정으로 만들며 app 계정에는 연결·DML/sequence 권한만 준다. 보호된 `OPENSQL_APP_*`, `OPENSQL_MIGRATION_*`, `JWT_SECRET`은 환경 변수로만 전달한다. 실행 명령은 `./backend/gradlew -p backend openSqlOutboxIsolatedTest --console=plain --no-daemon`이다. 종료 후 읽기 전용 대조를 하고 해당 시험 DB와 터널을 제거한다.

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

원래 DB에서는 오래된 `PENDING` 53건과 전부의 Job 외래키 참조를 확인했으므로 Dispatcher 활성화는 계속 **NO-GO**다. 사용자 선택에 따라 Event를 보존하고 별도 DB에서 정상 소비와 결정적 실패·재시도를 확인했다. 이는 **GCP OpenSQL primary에 대한 Dispatcher 코드 실행 증거**지만, 앱 A/B의 적용 설정·원래 backlog 원인·OpenProxy 경유·재인덱싱/soft delete의 실제 부작용·GCS 영구 삭제·primary 장애 후 수렴을 검증한 것은 아니다. 이 미검증 범위를 마치기 전에는 이슈 #439 전체 완료나 운영 Dispatcher 활성화를 주장하지 않는다.
