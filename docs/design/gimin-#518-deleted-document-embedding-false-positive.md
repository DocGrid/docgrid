# 정상 삭제된 문서의 MISSING_EMBEDDINGS 오탐 수정 (#518)

closes #518

## 1. 배경

#517 격리 시험에서 문서를 정상 삭제한 뒤 정합성 점검을 돌리자, 삭제된 문서의 현재 버전에
`MISSING_EMBEDDINGS`(CRITICAL, `repairable=false`) Issue가 새로 열려 끝까지 `OPEN`으로 남았다.

삭제 흐름은 다음과 같다.

1. `DELETE /api/documents/{id}`가 문서를 `DELETED`로 바꾼다. 버전과 `current_version_id`는 그대로 둔다.
2. `DOCUMENT_DELETED` Outbox Event가 그 문서의 ACTIVE Vector를 모두 `STALE`로 바꾼다. 물리 삭제는 하지 않는다.

`SyncConsistencyInspector.inspectChunksAndEmbeddings`는 "현재 버전이 `INDEXED`이고 Chunk 수가 ACTIVE Vector 수보다 많다"만 확인했다.
문서가 `DELETED`인지는 보지 않았다. 그래서 2단계가 정상으로 끝난 문서가 Chunk 5개·ACTIVE 0개로 보여 Vector 누락으로 보고됐다.

단위 시험에서는 영향이 하나 더 드러났다. 삭제 직후 Vector 일부만 ACTIVE로 남은 경우, 수정 전 코드는 이 삭제 문서를
`MISSING_EMBEDDINGS` **`repairable=true`** 로 분류했다. 이 상태에서 `REPAIR` 모드를 돌리면 삭제 문서에 재인덱싱 Event를 예약한다.
`IndexedVersionVectorRepairService`는 `INDEXED` 문서가 아니면 이 요청을 거부한다(`SYNC-003`). 따라서 이 Event는 실패할 것으로 본다.
다만 이 경로는 코드 분석이며 실제로 실행하지 않았다.

## 2. 변경

`inspectChunksAndEmbeddings`의 Vector 완전성 조건에 `document.status != DELETED`를 추가했다.

```java
if (version.getStatus() == DocumentVersionStatus.INDEXED
    && currentVersion
    && version.getDocument().getStatus() != DocumentStatus.DELETED
    && chunkCount > embeddingCount) {
```

- 삭제 문서의 ACTIVE Vector 잔여는 기존 `DELETED_DOCUMENT_RESIDUE` 검사가 그대로 보고한다.
- `MISSING_EMBEDDINGS`와 `MODEL_MISMATCH`는 같은 분기라 함께 제외된다. 삭제 문서는 검색 대상이 아니므로 Vector Set의 완전성을 요구하지 않는다.
- `MISSING_CHUNKS`, `MISSING_JOB`, `STALLED_VERSION`, `INVALID_CURRENT_VERSION`, `ORPHANED_DATA`는 바꾸지 않았다.
- DB 스키마, 설정, API 요청·응답 형식은 바꾸지 않았다.

## 3. API 계약과 오류

API 경로, 요청, 응답 필드, 오류 코드는 그대로다. 바뀌는 것은 점검 결과뿐이다.

| 상황 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 삭제 Event 처리 전, ACTIVE Vector가 Chunk 수만큼 남음 | `DELETED_DOCUMENT_RESIDUE` | 같음 |
| 삭제 Event 처리 전, ACTIVE Vector가 일부만 남음 | `DELETED_DOCUMENT_RESIDUE` + `MISSING_EMBEDDINGS`(`repairable=true`) | `DELETED_DOCUMENT_RESIDUE`만 |
| 삭제 Event 처리 후, Vector 전부 STALE | `MISSING_EMBEDDINGS`(`repairable=false`)가 계속 `OPEN` | Issue 없음. 이전 잔여물 Issue는 `RESOLVED` |
| 삭제되지 않은 현재 버전의 Vector 누락 | `MISSING_EMBEDDINGS` | 같음 |

`POST /admin/sync/reconcile`의 오류(400 잘못된 `mode`·음수 `cursor`, 401·403, 활성 모델 구성 오류 시 실행 이력 `FAILED`)는 바뀌지 않았다.

이미 열려 있던 오탐 Issue는 마이그레이션으로 지우지 않는다. 수정된 코드로 다음 점검을 돌리면, 그 버전에서 더 이상 관찰되지 않아
기존 `resolveMissingObservations` 경로로 `RESOLVED`가 된다.

## 4. 검증

[실행별 기록](../test-results/deleted-document-false-positive-20261016/README.md)

- 수정 전: `SyncConsistencyInspectorTest` 5건 중 새 시험 2건 실패, 기존 3건 통과.
  실패 관찰값은 #517 실시험과 같은 `{"activeEmbeddingCount":0,"currentModelEmbeddingCount":5,"allModelEmbeddingCount":5}`였다.
- 수정 후: 같은 시험 소스로 5건 모두 통과. 바뀐 입력은 서비스 파일 하나다.
- 전체 백엔드 `clean build`: 격리 PostgreSQL 17.8·pgvector 0.8.1·Valkey에서 251개 묶음, 1,497건 중 실패·오류 0건, 건너뜀 5건.
- 실제 삭제 경로: 같은 격리 DB에서 Dispatcher를 끈 채 문서를 삭제했다.
  - 수정 JAR는 삭제 직후 `DELETED_DOCUMENT_RESIDUE`만 탐지했다.
  - 수정 전 JAR로 삭제 Event를 처리하자 #517과 같은 `MISSING_EMBEDDINGS` 오탐이 재현됐다.
  - 이어서 수정 JAR로 점검하자 탐지 0건, 기존 오탐 `RESOLVED`, `REPAIR` 요청 0건, OPEN 0건이 됐다.

## 5. 범위 밖

- 삭제 문서의 처리 중 버전에서 나올 수 있는 `MISSING_JOB`·`STALLED_VERSION`은 이번에 판단하지 않았다.
- `DELETED_DOCUMENT_RESIDUE`의 `actualJson`이 실제 개수 대신 `">0"`만 남기는 문제는 그대로다.
- 주기 점검, A/B 다중 Backend는 시험하지 않았다.
