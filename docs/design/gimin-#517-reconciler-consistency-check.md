# 정합성 점검기(Reconciler) 설계와 검증 범위 (#517)

closes #517

## 1. 배경

DocGrid는 문서 원장(`documents`·`document_versions`)에서 검색용 파생 데이터(Embedding Job·Chunk·Vector)를 비동기로 만든다.
업로드 Transaction, Outbox Dispatcher, Worker가 단계별로 나누어 처리한다. 그래서 어느 한 단계가 실패하거나 누군가 DB를 직접 고치면
원장과 파생 데이터가 어긋날 수 있다. 예를 들어 처리 중인 버전에 Job이 없거나, 검색 중인 버전의 Vector 일부가 빠질 수 있다.

정합성 점검기(이하 Reconciler)는 이 어긋남을 찾아 Issue로 남긴다. 안전하다고 판정된 경우에만 기존 Outbox 경로로 복구를 예약한다.
구현은 2026-08-13 `c92bd7b`(#164, PR #171)에서 들어왔다. #168에서는 통합 시험으로 `MISSING_JOB` 복구를 확인했다.
하지만 실제 HTTP·Worker 경로로 Vector 누락까지 끝까지 복구되는지 확인한 기록과 설계 문서는 없었다.
이번 이슈에서는 **새 점검 로직, 지표, 설정 변경 없이** 기존 코드를 격리 환경에서 시험하고, 그 동작과 한계를 문서로 정리했다.

## 2. 구성과 흐름

```text
POST /admin/sync/reconcile {cursor, mode}       (수동, 배치 1개)
SyncReconciliationScheduler                      (주기, 기본 꺼짐)
        │
        ▼
SyncReconciliationOrchestrator ── 실행 이력 시작·완료·실패를 독립 Transaction으로 기록
        │
        ▼
SyncReconciliationBatchService  (한 Transaction)
  ├─ cursor == 0 이면 SyncOrphanInspector (전역 고아 검사 1회)
  ├─ id > cursor 인 DocumentVersion을 batch-size(기본 100)개 조회
  ├─ 버전마다 SyncConsistencyInspector.inspect() ─ 읽기만 함
  ├─ SyncConsistencyIssueService.detect() ─ issueKey로 같은 Issue 행에 수렴
  ├─ REPAIR 이고 repairable 이고 Issue가 OPEN 이면
  │     SyncEventWriter.recordDocumentReindexRequested() → Issue REPAIRING
  └─ 이번에 관찰되지 않은 활성 Issue → RESOLVED
        │
        ▼ (Outbox)
Dispatcher → DocumentVersionSyncEventHandler
  ├─ 최근 Job 없음 + 처리 중 버전 → 새 Job 생성
  ├─ 최근 Job FAILED → 기존 수동 재처리 경로
  ├─ 최근 Job PENDING/PROCESSING → 재사용(아무것도 안 함)
  └─ 최근 Job INDEXED → IndexedVersionVectorRepairService
         (문서 INDEXING, 버전 CHUNKED로 되돌리고 새 Job 생성)
        │
        ▼
Worker → 누락 Chunk의 Vector만 저장 → INDEXED
        │
        ▼
다음 점검에서 같은 issueKey가 관찰되지 않으면 Issue RESOLVED
```

Issue는 복구 Event가 성공했다고 바로 닫히지 않는다. **다음 점검에서 문제가 다시 관찰되지 않아야** `RESOLVED`가 된다.

## 3. 8가지 검사

| 유형 | 탐지 조건 (코드 기준) | 심각도 | 자동 복구 | Issue 범위 | 이번 시험 |
| --- | --- | --- | --- | --- | --- |
| `MISSING_JOB` | 버전이 `UPLOADED`·`PARSING`·`CHUNKED`·`EMBEDDING`인데 `PENDING`·`PROCESSING` Job이 없음 | ERROR | 가능 | 버전·모델 | 완결 증명 B |
| `MISSING_CHUNKS` | 버전이 `CHUNKED`·`EMBEDDING`·`INDEXED`인데 Chunk 0개 | CRITICAL | 불가 | 버전·모델 | 미주입 |
| `MISSING_EMBEDDINGS` | 현재 `INDEXED` 버전의 Chunk 수가 활성 모델 ACTIVE Vector 수보다 많음 | CRITICAL | 활성 모델 Vector가 모두 ACTIVE이고 일부만 빠졌을 때만 | 버전·모델 | 완결 증명 A |
| `MODEL_MISMATCH` | 위와 같지만 활성 모델 Vector가 0이고 다른 모델 Vector가 Chunk 수 이상 | CRITICAL | 불가 | 버전·모델 | 미주입 |
| `INVALID_CURRENT_VERSION` | `INDEXED` 문서의 `current_version_id`가 없거나, 가리키는 버전이 `INDEXED`가 아니거나 다른 문서 소속 | CRITICAL | 불가 | 문서 | 미주입 |
| `STALLED_VERSION` | 처리 중 버전의 `updated_at`이 임계값(기본 15분)보다 오래됨. 살아 있는 Job이 있으면 WARNING, 없으면 ERROR | WARNING·ERROR | 불가 | 버전·모델 | 보고 사례 |
| `DELETED_DOCUMENT_RESIDUE` | `DELETED` 문서의 현재 버전을 검사할 때 그 문서에 ACTIVE Vector가 남아 있음 | CRITICAL | 불가 | 문서 | 보고 사례 |
| `ORPHANED_DATA` | 버전·문서를 잃은 Chunk, 또는 Chunk·문서·버전·모델 중 하나를 잃은 Vector가 하나라도 있음. cursor 0일 때만 전역 1회 | CRITICAL | 불가 | 전역 | 미주입 |

- 한 버전에서 여러 문제가 함께 나오면 모두 기록한다. 이번 시험에서도 `STALLED_VERSION`과 `MISSING_JOB`이 같은 버전에서 함께 나왔다.
- 같은 문제는 `issueKey`(예: `MISSING_JOB:VERSION:5:MODEL:1`)로 같은 행에 모인다. 다시 관찰되면 심각도·근거 JSON·마지막 관찰 시각만 갱신한다.
- 점검에는 활성·검색 가능 임베딩 모델이 정확히 하나 있어야 한다. 0개나 2개 이상이면 배치가 실패하고 실행 이력이 `FAILED`로 남는다.

## 4. 왜 대부분은 보고만 하는가

자동 복구는 **기존 데이터를 지우거나 덮지 않고, 기존 Job Queue 경로로 빠진 것만 다시 만들 수 있을 때**로 제한했다.
코드 주석에도 "currentVersion 모순, 기존 Vector 행의 상태 손상과 삭제 잔여물은 항상 보고 전용", "데이터 삭제나 currentVersion 변경은 수행하지 않는다"고 적혀 있다.

| 유형 | 자동으로 고치지 않는 이유 |
| --- | --- |
| `MISSING_CHUNKS` | 다시 만들려면 원본 파일을 다시 Parsing해야 한다. 원본 저장소 상태를 Reconciler가 보장할 수 없고, 이미 `INDEXED`인 버전을 앞 단계로 되돌리는 판단이 필요하다 |
| `MODEL_MISMATCH` | 모델 전환은 별도 흐름(`EMBEDDING_MODEL_ACTIVATED`)이 맡는다. 다른 모델 Vector를 지우거나 대체하는 결정은 운영 판단이다 |
| `INVALID_CURRENT_VERSION` | 어느 버전을 현재 버전으로 볼지 Reconciler가 정할 근거가 없다. 포인터를 바꾸면 사용자에게 보이는 문서 내용이 바뀐다 |
| `STALLED_VERSION` | 원인이 느린 Worker인지, 멈춘 Worker인지, Job 누락인지 단정할 수 없다. Job 누락은 `MISSING_JOB`이 따로 잡아 복구한다 |
| `DELETED_DOCUMENT_RESIDUE` | 정상 경로는 `DOCUMENT_DELETED` Outbox Event다. 이 Event가 Vector를 `STALE`로 바꾼다. Reconciler가 같은 일을 하면 삭제 경로가 둘이 된다 |
| `ORPHANED_DATA` | 해결하려면 물리 삭제가 필요하다 |
| `MISSING_EMBEDDINGS` 일부 | 활성 모델 Vector 중 ACTIVE가 아닌 행이 섞여 있으면 어떤 행을 믿을지 정할 수 없어 보고만 한다 |

자동 복구도 다음 조건을 모두 통과해야 한다.

1. Inspector가 `repairable=true`로 표시했다.
2. Issue가 `OPEN`이다. `REPAIRING`·`IGNORED`는 다시 예약하지 않는다.
3. Outbox 멱등 키에 Issue Key와 복구 시도 번호가 들어가, 같은 시도는 Event 하나로 모인다.
4. Handler는 살아 있는 Job이 이미 있으면 새 Job을 만들지 않고 재사용한다. Vector 복구를 맡는 `IndexedVersionVectorRepairService`는 버전·문서 행을 잠근 뒤 대상을 다시 검증한다. 현재 버전이 아니거나, 살아 있는 Job이 있거나, Vector Set이 이미 완전하면 거부한다.

## 5. 두 모드

| | `DRY_RUN` | `REPAIR` |
| --- | --- | --- |
| Issue 생성·갱신·종결 | 한다 | 한다 |
| 실행 이력(`sync_reconciliation_runs`) | 남긴다 | 남긴다 |
| 수동 실행 시 감사 Action | 남긴다 | 남긴다 |
| 복구 Outbox Event | 만들지 않는다 | 안전한 `OPEN` Issue에만 만든다 |

**`DRY_RUN`은 읽기 전용이 아니다.** Issue와 실행 이력을 DB에 쓴다. 차이는 복구 Event를 만드는지뿐이다.

## 6. API 명세

모든 경로는 `/admin/**`이므로 ADMIN 권한이 필요하다. 인증 실패는 401, 권한 부족은 403이다.

### `POST /admin/sync/reconcile`

| 항목 | 내용 |
| --- | --- |
| 요청 | `{"cursor": 0, "mode": "DRY_RUN"}`. `mode` 필수(`DRY_RUN`·`REPAIR`), `cursor` 0 이상 |
| 동작 | `id > cursor`인 버전을 **배치 하나**(기본 100개)만 검사한다. cursor 0이면 전역 고아 검사도 한다 |
| 응답 200 | `runId`, `startCursor`, `endCursor`, `scannedCount`, `detectedCount`, `repairRequestedCount`, `hasMore`, `action` |
| 전체 점검 | `hasMore=true`면 `endCursor`를 다음 `cursor`로 넣어 `false`가 될 때까지 반복한다 |
| 400 | `mode` 누락·알 수 없는 값, 음수 `cursor` |
| 실패 | 검사 Transaction 예외 시 Issue·Event 변경은 롤백되고, 실행 이력만 `FAILED`와 오류 코드로 남는다. 감사 Action은 남지 않는다 |

`hasMore`는 "이번 배치가 batch-size만큼 찼는가"로 계산한다. 그래서 버전 수가 batch-size의 배수면 마지막에 빈 배치가 한 번 더 돈다.

### 그 밖의 관련 API

| API | 내용 | 오류 |
| --- | --- | --- |
| `GET /admin/sync/summary` | Event·Issue·마지막 점검 요약 | — |
| `GET /admin/sync/issues` | `status`·`issueType`·`severity` 필터, 페이지 크기 1~100 | 400 |
| `POST /admin/sync/issues/{id}/repair` | Issue 하나를 행 잠금 뒤 복구 예약 | 404 `SYNC-005`, 409 `SYNC-006`(OPEN이 아니거나 `repairable=false`) |
| `POST /admin/sync/issues/{id}/ignore` | 사유와 함께 `IGNORED`로 종결 | 404 `SYNC-005`, 409 `SYNC-007`(OPEN이 아님) |

### 관리 화면

관리 화면의 "상태 검사 실행" 버튼은 `{"mode": "REPAIR", "cursor": 0}`으로 배치 하나를 실행한다(`frontend/app/features/AdminPages.tsx`).
버튼을 한 번 누르는 것은 `DRY_RUN`이 아니며, 버전이 100개를 넘으면 전체 점검도 아니다. 이번 시험에서 화면은 열지 않았고 같은 API를 직접 호출했다.

## 7. Issue 생명주기

```text
        탐지                    REPAIR 예약
 (없음) ────▶ OPEN ───────────────▶ REPAIRING
               │  ▲                      │
     관리자 무시 │  │ 다시 관찰             │ 다음 점검에서 관찰 안 됨
               ▼  │                      ▼
            IGNORED  RESOLVED ◀──────────┘
                     (OPEN에서도 관찰 안 되면 RESOLVED)
```

- `RESOLVED` 뒤 같은 문제가 다시 관찰되면 `OPEN`으로 돌아가고 해결 정보는 지워진다.
- `IGNORED`는 다시 관찰돼도 상태를 유지하고 근거만 갱신한다.
- `MISSING_JOB`의 `RESOLVED`는 "살아 있는 Job이 다시 생겼다"는 뜻이다. 문서가 검색 가능해졌다는 뜻은 아니다.

## 8. 주기 점검이 기본으로 꺼진 이유

`sync.reconciliation.enabled` 기본값은 `false`, 모드 기본값은 `DRY_RUN`, 주기는 5분이다.
도입 커밋과 PR #171 본문에는 기본값을 끈 이유가 남아 있지 않다. 아래는 **코드 동작과 이번 시험으로 확인한 사실에 근거한 판단**이다.

1. **`DRY_RUN`도 쓰기 작업이다.** 켜 두면 모든 환경에서 5분마다 Issue·실행 이력을 쓴다.
2. **`REPAIR`는 사용자에게 보이는 상태를 바꾼다.** `MISSING_EMBEDDINGS` 복구 중에는 문서가 `INDEXING`이 되어 일반 검색에서 빠진다. 이번 시험에서 약 14초였다. Worker는 Chunk 전체를 다시 임베딩하고 빠진 것만 저장하므로, 임베딩 서버 부하도 생긴다.
3. **다중 인스턴스 중복 방지가 없다.** 아래 9절 참고.
4. **오탐이 있었다.** 이번 시험에서 정상 삭제된 문서에 `MISSING_EMBEDDINGS`가 남는 것을 확인했다(10절, 수정은 #518). 수정 전 코드로 켜 두면 삭제할 때마다 CRITICAL Issue가 쌓인다.

그래서 현재는 운영자가 수동 API로 범위와 모드를 정해 실행하는 것을 기본 경로로 본다.

## 9. A/B 중복 실행 한계 (분석, 시험하지 않음)

이번 시험은 Backend 1대였다. 아래는 코드를 읽고 내린 분석이며 실제 A/B 환경에서 재현하지 않았다.

- **스케줄러 중복 방지는 JVM 하나 안에서만 동작한다.** `SyncReconciliationScheduler`는 `AtomicBoolean`으로 같은 인스턴스의 겹친 실행만 막는다. cursor도 메모리의 `AtomicLong`이라 인스턴스마다 따로 있고, 재시작하면 0으로 돌아간다. A와 B에서 모두 켜면 같은 범위를 각자 점검한다.
- **수동 실행은 스케줄러 가드를 거치지 않는다.** `POST /admin/sync/reconcile`은 Orchestrator를 바로 부른다. 그래서 한 인스턴스 안에서도 주기 실행과 겹칠 수 있다.
- **새 Issue를 동시에 만들면 한쪽이 실패할 수 있다.** 배치는 `findByIssueKey`를 잠금 없이 조회한 뒤 없으면 INSERT한다. 두 배치가 같은 새 문제를 동시에 보면, 한쪽은 `issue_key` 유일 제약에 걸려 배치 전체가 롤백되고 실행 이력이 `FAILED`가 될 것으로 본다.
- **복구 Event는 하나로 모일 가능성이 높다.** 두 배치가 같은 `OPEN` Issue를 같은 시도 번호로 보면 Outbox 멱등 키가 같아 `ON CONFLICT`로 Event 하나에 모인다. 다만 Issue 행 갱신에는 잠금·버전 컬럼이 없어, 복구 시도 횟수가 실제보다 적게 남을 수 있다.
- **대응은 이번 범위 밖이다.** 주기 점검을 켜야 한다면 한 인스턴스에서만 켜는 것이 현재 가능한 운영 방법이다. DB advisory lock 같은 분산 잠금은 후속 과제다.

## 10. 검증 결과와 알려진 한계

2026-10-16 로컬 격리 환경(develop `f238caf` JAR, PostgreSQL 17.8·pgvector 0.8.1, BGE-M3, 시험 문서 4건)에서 실행했다.
[실행별 기록](../test-results/reconciler-repair-20261016/README.md)

| 실행 | 결과 |
| --- | --- |
| 기준선 | 버전 2개, Issue 0건 |
| 증명 A `MISSING_EMBEDDINGS` | Vector 1행 삭제 → 탐지 → Event 1 → 새 Job 1 → `INDEXED` → `RESOLVED`. 기존 Vector 4행 ID·해시 보존, 누락 1행만 같은 해시로 추가, 검색 1위가 복구 Vector |
| 증명 B `MISSING_JOB` | Job 삭제 → Dispatcher 재생성 없음 → 탐지 → Event 1 → 새 Job → Worker 켠 뒤 `INDEXED`, 검색 반환 |
| 보고 `DELETED_DOCUMENT_RESIDUE` | `CRITICAL` 보고, `REPAIR` 0건, 단건 복구 409. 잔여 중 검색 0건. 삭제 Event 처리 뒤 `RESOLVED` |
| 보고 `STALLED_VERSION` | WARNING → Job 삭제 뒤 `MISSING_JOB`과 동시 탐지·ERROR. `REPAIR`는 `MISSING_JOB`만 예약. Worker 처리 뒤 `RESOLVED` |
| 최종 | Vector·live Job 중복 0, 미처리 Outbox 0. OPEN Issue 1건(아래 오탐) |

알려진 한계:

- **삭제 문서 오탐(이번 시험에서 발견, 수정은 #518).** 삭제 Event가 정상 처리되어 Vector가 모두 `STALE`이 되면, 삭제된 문서의 현재 버전에 `MISSING_EMBEDDINGS`(CRITICAL, 보고 전용)가 열린다. Chunk·Vector 검사가 문서의 `DELETED` 상태를 제외하지 않았기 때문이다. #518에서 삭제 문서를 이 검사에서 제외하고 실제 삭제 경로로 재시험했다([수정 설계](gimin-%23518-deleted-document-embedding-false-positive.md)).
- `DELETED_DOCUMENT_RESIDUE`의 `actualJson`은 실제 개수 대신 `">0"`만 기록한다.
- 수동 API 한 번은 배치 하나만 돈다. 전체 점검은 호출자가 cursor를 이어 가야 한다.
- `MISSING_CHUNKS`·`MODEL_MISMATCH`·`INVALID_CURRENT_VERSION`·`ORPHANED_DATA`는 이번에 주입하지 않았다. 또 `backend/src/test`에서 이 네 유형 이름으로 찾히는 자동 시험도 없다. 이 네 유형의 탐지 조건은 코드로만 확인한 상태다. 기존 자동 시험은 `MISSING_JOB`(#168 통합 시험 등)과 `MISSING_EMBEDDINGS`(`SyncConsistencyInspectorTest`)를 다룬다.
- 주기 점검, A/B 다중 Backend, 대량 문서, 관리 화면 조작은 시험하지 않았다.
