# 보고 사례: 삭제 문서의 검색 Vector 잔여 — run-deleted-residue-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 삭제된 문서에 ACTIVE Vector가 남은 상태를 탐지·보고만 하는지, Outbox 처리 뒤 Issue가 닫히는지 확인 |
| 시각 | 2026-10-16 16:01:15~16:02:43 KST |
| 환경 | 2단계 Backend(Worker·Dispatcher 끔) → 3단계 Backend(Dispatcher만 켬). 시험 문서 C(`documentId=4`, `versionId=4`, Chunk 5개) |
| 주입 | SQL 없음. Dispatcher를 끈 상태에서 `DELETE /api/documents/4`로 정상 삭제해, `DOCUMENT_DELETED` Event 처리 전 구간을 만들었다 |
| 성공 기준 | `DELETED_DOCUMENT_RESIDUE` 탐지, `REPAIR`와 단건 복구가 아무것도 예약하지 않음, Event 처리 뒤 재점검에서 Issue `RESOLVED` |
| 결과 | **부분 통과.** 위 기준은 모두 충족했지만, 재점검에서 기대하지 않은 `MISSING_EMBEDDINGS` Issue가 새로 열렸다(아래 참고) |
| 정리 | 시험 DB는 전체 시험 뒤 컨테이너째 삭제. 이 결과에 대한 코드 수정은 하지 않았다 |

## 흐름

| 시각 (KST) | 단계 | 관측 |
| --- | --- | --- |
| 16:01:15 | 사전 점검 `DRY_RUN` | 버전 4개, 탐지 0건 |
| 16:01:15 | 문서 삭제 | HTTP 204. 문서 `DELETED`, 버전 `INDEXED` 유지, ACTIVE Vector 5, `DOCUMENT_DELETED` Event `PENDING` |
| 16:01:29 | 문서 C 주제로 검색 | 결과 **0건**(`NO_CONTEXT` 안내) |
| 16:02:01 | 탐지 `DRY_RUN` | Issue **2번** `DELETED_DOCUMENT_RESIDUE`, `CRITICAL`, `repairable=false`, `OPEN` |
| 16:02:10 | `REPAIR` | 탐지 1건, 복구 요청 **0건** |
| 16:02:10 | `POST /admin/sync/issues/2/repair` | HTTP **409** `SYNC-006`. 감사 Action 미기록 |
| 16:02:40 | Dispatcher 켬 | 3단계 Backend 기동 |
| 16:02:42 | Event 처리 | `DOCUMENT_DELETED` `PROCESSED`. Vector 5행 `ACTIVE → STALE`(물리 삭제 없음) |
| 16:02:43 | 재점검 `DRY_RUN` | Issue 2번 `RESOLVED`. **Issue 3번 `MISSING_EMBEDDINGS` 새로 `OPEN`** |

## 삭제 문서의 검색 노출

삭제 직후 Vector가 ACTIVE로 남아 있는 동안에도 검색 결과는 0건이었다. 유사도가 낮아서인지 상태 필터 때문인지 가르기 위해, 같은 질문 Vector와 버전별 ACTIVE Vector의 최대 코사인 유사도를 SQL로 직접 계산했다([쿼리](2-direct-similarity-probe.sql), [결과](2-direct-similarity-probe.txt)).

| 버전 | 문서 상태 | 최대 유사도 |
| ---: | --- | ---: |
| 4 (문서 C) | `DELETED` | 0.5061 |
| 3 (문서 A) | `INDEXED` | 0.3265 |

문서 C의 0.5061은 하이브리드 검색의 벡터 통과 기준 0.45보다 높다. 그런데도 결과가 0건이었으므로, 이 시험에서는 삭제 문서가 문서 상태 조건으로 검색 후보에서 빠졌다고 본다. 따라서 이 Issue는 "삭제 문서가 검색에 노출된다"는 뜻이 아니다. 검색 경로 밖에 ACTIVE Vector가 남은 정합성 위반을 보고한다.

## 기대와 다른 결과: 정상 삭제 뒤 `MISSING_EMBEDDINGS` 보고

삭제 Event가 정상 처리되어 Vector가 모두 STALE이 되자, 같은 재점검에서 다음 Issue가 열렸다([상세](5-unexpected-issue-3.txt)).

```text
issue_key     MISSING_EMBEDDINGS:VERSION:4:MODEL:1
severity      CRITICAL, repairable=false, OPEN
document      DELETED, version INDEXED (documents.current_version_id = 4)
actual_json   {"activeEmbeddingCount":0,"currentModelEmbeddingCount":5,"allModelEmbeddingCount":5}
```

- 코드 기준 추정 원인: `SyncConsistencyInspector.inspectChunksAndEmbeddings`는 버전이 `INDEXED`이고 현재 버전이며 Chunk 수가 ACTIVE Vector 수보다 많으면 보고한다. 문서가 `DELETED`인지는 보지 않는다.
- 영향: 정상 삭제된 문서마다 해소되지 않는 `CRITICAL` Issue가 하나씩 남을 수 있다. `repairable=false`라 복구 Event는 만들지 않는다. 이후 [최종 점검](../run-final-check-01/README.md)까지 이 Issue만 `OPEN`으로 남았다.
- 처리: 시험 지시에 따라 이 시험 중에는 코드를 고치지 않고 기록만 했다. 수정과 재시험은 [#518](https://github.com/DocGrid/docgrid/issues/518)의 [검증 기록](../../deleted-document-false-positive-20261016/README.md)에 있다.

## 증거

- [실행 조건](conditions.txt), [업로드 응답](upload-response.json), [삭제 응답](1-delete-response.txt)
- 상태: [삭제 전](state-0-before-delete.txt), [삭제 직후](state-1-after-delete.txt), [복구 시도 뒤](state-2-after-repair-attempts.txt), [Dispatcher 처리 뒤](state-3-after-dispatcher.txt), [Dispatcher 재개 중 상태 변화](5-timeline-dispatcher-on.txt)
- API: [사전 점검](0-precheck-dry-run-batch1.json), [검색 응답](2-search-during-residue.json), [검색 원장](2-search-results-db.txt), [탐지](3-detect-dry-run-batch1.json), [OPEN Issue](3-issues-open.json), [REPAIR](4-repair-batch1.json), [단건 복구 거절](4-issue-repair-endpoint.txt), [재점검](5-recheck-dry-run-batch1.json), [전체 Issue](5-issues-all.json)
