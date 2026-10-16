# 완결 증명 A: Vector 1행 누락 복구 — run-missing-embeddings-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 현재 INDEXED 버전의 활성 모델 Vector가 1행 빠졌을 때 탐지 → 복구 예약 → Worker 처리 → 재점검 종결까지 이어지는지 확인 |
| 시각 | 2026-10-16 15:58:14~16:00:21 KST |
| 환경 | 1단계 Backend(Worker·Dispatcher 켬), 시험 문서 A(`documentId=3`, `versionId=3`, Chunk 5개) |
| 주입 | [inject.sql](inject.sql): `chunk_index=2`의 ACTIVE Vector(`embedding_id=12`) 1행 삭제. 정확히 1행이 아니면 롤백 |
| 성공 기준 | `MISSING_EMBEDDINGS` 탐지, `REPAIR`가 Event 1건 예약, 기존 Vector 보존, 누락 1행만 추가, 재점검에서 Issue `RESOLVED`, 검색 결과 반환 |
| 결과 | 통과 |
| 정리 | 시험 DB는 전체 시험 뒤 컨테이너째 삭제 |

## 흐름

| 시각 (KST) | 단계 | 관측 |
| --- | --- | --- |
| 15:58:14 | 사전 점검 `DRY_RUN` | 버전 4개, 탐지 0건. 이 버전의 검색·인용 이력 0건 |
| 15:58:26 | 주입 | ACTIVE Vector 5 → 4 |
| 15:58:34 | 탐지 `DRY_RUN` | Issue **1번** `MISSING_EMBEDDINGS`, `CRITICAL`, `repairable=true`, `OPEN`. 복구 Event 0건 |
| 15:58:56.134 | `REPAIR` | 탐지 1건, 복구 요청 **1건**. Issue `REPAIRING` |
| 15:58:56.156 | Outbox 기록 | `DOCUMENT_REINDEX_REQUESTED` `7cc6bc1b…` |
| 15:58:56.700 | Dispatcher 처리 | Event `PROCESSED`, 전달 시도 1회 `SUCCEEDED`. 새 Job **3번** `PENDING` 생성 |
| 15:58:56 | 상태 전환 | 문서 `INDEXED → INDEXING`, 버전 `INDEXED → CHUNKED` |
| 15:59:01.907 | Worker claim | Job 3 `PROCESSING`. Parsing 단계 없이 `CHUNKED → EMBEDDING` |
| 15:59:11.024 | Worker 완료 | 버전·문서 `INDEXED`, ACTIVE Vector 5. Attempt 1회 `SUCCESS`, 9,104ms |
| 15:59:23 | 재점검 `DRY_RUN` | 탐지 0건. Issue 1번 `RESOLVED` |
| 15:59:53 | 검색 | 1위가 복구된 Vector(`embedding_id=15`, 유사도 0.620245) |

`REPAIR` 요청부터 `INDEXED`까지 약 **14.9초**였다. 이 가운데 Job 생성부터 Worker claim까지 약 5.2초였다. Worker의 빈 대기 polling 간격(최대 10초) 때문으로 보이며, 따로 측정하지 않았다.

## 보존·추가 확인

| chunk_index | 주입 전 embedding_id | 복구 후 embedding_id | vector_hash 앞 8자 | 비고 |
| ---: | ---: | ---: | --- | --- |
| 0 | 10 | 10 | `4fbb852e` | ID·해시·생성 시각 동일 |
| 1 | 11 | 11 | `54d2b9db` | ID·해시·생성 시각 동일 |
| 2 | 12 | **15** | `a74e1a91` | 새 행. 삭제 전과 같은 해시 |
| 3 | 13 | 13 | `2df1945c` | ID·해시·생성 시각 동일 |
| 4 | 14 | 14 | `955eaf4a` | ID·해시·생성 시각 동일 |

- Job 수: 1 → **2**. 새 Job 3번의 `source_event_id`가 복구 Event와 같다.
- 복구 중 임베딩 서버 `POST /embed/batch` 호출은 **2회**였다. Worker는 Chunk 5개를 모두 다시 계산하고, 저장은 없던 1행만 했다.
- 같은 Chunk·모델 Vector 중복 0건(최종 [무결성 확인](../run-final-check-01/integrity.txt)).

## 해석 범위

- Issue는 Worker 완료로 바로 닫히지 않는다. 다음 점검에서 같은 Issue Key가 다시 관찰되지 않아야 `RESOLVED`가 된다.
- 복구 동안(15:58:56~15:59:11, 약 14초) 문서 A는 `INDEXING` 상태였다. 일반 검색은 `INDEXED` 문서만 대상으로 하므로 이 구간에는 문서 전체가 검색에서 빠진다. 이 구간의 검색은 호출하지 않았고 상태로만 확인했다.
- 검색 뒤 비동기 RAG는 Ollama 주소를 닫힌 로컬 포트로 지정해 `FAILED`가 됐다. RAG는 이번 시험 범위가 아니다.
- 문서 1건, Chunk 5개, 로컬 장비 1대의 관측이다. 시간 값은 처리 속도 기준이 아니다.

## 증거

- 주입: [inject.sql](inject.sql), [실행 출력](inject-output.txt), [검색 이력 사전 확인](precheck-search-history.txt)
- 상태: [주입 전](state-0-before-injection.txt), [주입 후](state-1-after-injection.txt), [REPAIR 직후](state-2-right-after-repair.txt), [Worker 완료 후](state-3-after-worker.txt), [Job·Event 상세](state-3-job-event-detail.txt), [상태 변화 기록](2-timeline.txt)
- API: [사전 점검](0-precheck-dry-run-batch1.json), [탐지](1-detect-dry-run-batch1.json), [OPEN Issue](1-issues-open.json), [REPAIR](2-repair-batch1.json), [재점검](3-recheck-dry-run-batch1.json), [전체 Issue](3-issues-all.json), [검색 응답](4-search-after-repair.json), [검색 원장](4-search-results-db.txt), [업로드 응답](upload-response.json)
