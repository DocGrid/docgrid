# 완결 증명 B: 처리 Job 누락 복구 — run-missing-job-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 처리 중 버전이 실행할 Job을 잃었을 때 탐지 → 복구 예약 → 새 Job → Worker 처리 → INDEXED까지 이어지는지 확인 |
| 시각 | 2026-10-16 16:03:25~16:06:06 KST |
| 환경 | 3단계 Backend(Worker 끔, Dispatcher 켬) → 4단계 Backend(Worker·Dispatcher 켬). 시험 문서 B(`documentId=5`, `versionId=5`) |
| 주입 | [inject.sql](inject.sql): attempt·indexing_event가 0건인 `PENDING` Job 4번 삭제. 처리 이력이 있으면 롤백 |
| 성공 기준 | `MISSING_JOB` 탐지, `REPAIR`가 Event 1건 예약, Dispatcher가 새 Job 생성, Worker 처리 뒤 `INDEXED`, 검색 결과 반환 |
| 결과 | 통과 |
| 정리 | 시험 DB는 전체 시험 뒤 컨테이너째 삭제 |

## 흐름

| 시각 (KST) | 단계 | 관측 |
| --- | --- | --- |
| 16:03:25 | 업로드 | 문서·버전 `UPLOADED`, Job 4번 `PENDING`. `DOCUMENT_VERSION_CREATED` Event 0.17초 뒤 `PROCESSED` |
| 16:03:36 | 사전 점검 `DRY_RUN` | 버전 5개, 탐지 1건. 문서 C의 Issue 3번이 다시 관찰된 것이며 이 시험과 무관 |
| 16:03:37 | 주입 | Job 4번 삭제(attempt 0, indexing_event 0이라 FK에 막히지 않음) |
| 16:03:47 | 10초 관찰 | Job **0건**. 업로드 Event가 이미 `PROCESSED`라 Dispatcher가 Job을 다시 만들지 않았다 |
| 16:03:54 | 탐지 `DRY_RUN` | Issue **4번** `MISSING_JOB`, `ERROR`, `repairable=true`, `OPEN`. `{"liveJob":false,"versionStatus":"UPLOADED"}` |
| 16:04:07.020 | `REPAIR` | 복구 요청 **1건**. Issue `REPAIRING` |
| 16:04:07.078 | Outbox 기록 | `DOCUMENT_REINDEX_REQUESTED` `e8c1e0bb…` |
| 16:04:07.874 | Dispatcher 처리 | Event `PROCESSED`. 새 Job **5번** `PENDING`, `source_event_id`가 복구 Event와 같음 |
| 16:04:11 | 재점검 `DRY_RUN` (Worker 끔) | Issue 4번 `RESOLVED`. 버전은 아직 `UPLOADED` |
| 16:05:31 | Worker 켬 | 4단계 Backend 기동 |
| 16:05:53 | Worker 완료 | Job 5번·버전·문서 `INDEXED`, Chunk 5·ACTIVE Vector 5 |
| 16:06:06 | 최종 `DRY_RUN` | 이 버전의 Issue 없음 |
| 16:06:07 | 검색 | 1·2위가 문서 B(유사도 0.660189, 0.610054) |

## 해석 범위

- Dispatcher 재생성 여부: 이번 실행에서는 업로드 Event가 0.17초 만에 처리됐다. 그래서 Job을 지운 뒤에는 Outbox가 Job을 다시 만들지 않았고, 복구는 Reconciler의 `REPAIR`로만 일어났다. 업로드 Event가 처리되기 전에 Job이 사라지는 경우는 이번에 만들지 않았다. 코드상으로는 그때 Dispatcher가 같은 Event로 Job을 새로 만든다.
- `MISSING_JOB`의 `RESOLVED`는 "살아 있는 Job이 다시 생겼다"는 뜻이다. 문서가 검색 가능해졌다는 뜻이 아니다. 이번에는 Worker를 켠 뒤 `INDEXED`까지 별도로 확인했다.
- Worker를 끈 동안 버전은 약 2분간 `UPLOADED`였다. 정체 임계값 15분보다 짧아 `STALLED_VERSION`은 나오지 않았다.
- 문서 1건의 로컬 관측이다.

## 증거

- 주입: [inject.sql](inject.sql), [실행 출력](inject-output.txt), [실행 조건](conditions.txt), [업로드 응답](upload-response.json)
- 상태: [주입 전](state-0-before-injection.txt), [주입 10초 뒤](state-1-after-injection-10s.txt), [REPAIR 뒤](state-2-after-repair.txt), [Worker 완료 뒤](state-3-after-worker.txt), [Worker 끔 구간 변화](2-timeline-worker-off.txt), [Worker 켬 구간 변화](4-timeline-worker-on.txt)
- API: [사전 점검](0-precheck-dry-run-batch1.json), [탐지](1-detect-dry-run-batch1.json), [OPEN Issue](1-issues-open.json), [REPAIR](2-repair-batch1.json), [재점검](3-recheck-dry-run-worker-off-batch1.json), [검색 응답](5-search-after-indexed.json)
