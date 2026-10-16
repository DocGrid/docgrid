# 보고 사례: 정체 버전과 Job 누락 동시 탐지 — run-stalled-version-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 오래 진행되지 않은 처리 중 버전을 보고만 하는지, Job까지 없을 때 `MISSING_JOB`과 함께 잡히는지, `REPAIR`가 둘 중 안전한 쪽만 처리하는지 확인 |
| 시각 | 2026-10-16 16:04:29~16:06:06 KST |
| 환경 | 3단계 Backend(Worker 끔, Dispatcher 켬) → 4단계 Backend(Worker·Dispatcher 켬). 시험 문서 D(`documentId=6`, `versionId=6`). 정체 임계값은 기본값 15분, 설정 변경 없음 |
| 주입 1 | [inject-1-backdate.sql](inject-1-backdate.sql): 버전 `updated_at`을 앱이 기록한 값보다 20분 이르게 변경 |
| 주입 2 | [inject-2-delete-job.sql](inject-2-delete-job.sql): attempt·indexing_event 0건인 `PENDING` Job 6번 삭제 |
| 성공 기준 | `STALLED_VERSION`은 `repairable=false`로 보고만 하고, Job이 없으면 `MISSING_JOB`과 동시 탐지되며, `REPAIR`는 `MISSING_JOB`만 예약 |
| 결과 | 통과 |
| 정리 | 시험 DB는 전체 시험 뒤 컨테이너째 삭제 |

## 흐름

| 시각 (KST) | 단계 | 관측 |
| --- | --- | --- |
| 16:04:29 | 업로드 | `UPLOADED`, Job 6번 `PENDING` |
| 16:04:40 | 주입 1 | `updated_at` 16:04:29 → 15:44:29 |
| 16:04:40 | 탐지 `DRY_RUN` | Issue **5번** `STALLED_VERSION`, **`WARNING`**, `repairable=false`. `{"liveJob":true}` |
| 16:04:50 | 주입 2 | Job 6번 삭제 |
| 16:04:53 | 탐지 `DRY_RUN` | Issue **6번** `MISSING_JOB` `ERROR` `repairable=true` 새로 열림. 같은 Issue 5번은 **`ERROR`로 상향**, `{"liveJob":false}` |
| 16:05:03 | `REPAIR` | 탐지 3건(문서 C의 Issue 3번 포함), 복구 요청 **1건**. Issue 6번만 `REPAIRING`, Issue 5번은 `OPEN` 유지 |
| 16:05:03.564 | Dispatcher 처리 | `DOCUMENT_REINDEX_REQUESTED` `a9bc2b82…` `PROCESSED`, 새 Job 7번 `PENDING` |
| 16:05:07 | 재점검 `DRY_RUN` (Worker 끔) | Issue 6번 `RESOLVED`. Issue 5번은 `OPEN`, 살아 있는 Job이 생겨 **`WARNING`으로 하향** |
| 16:05:31 | Worker 켬 | 4단계 Backend 기동 |
| 16:05:50 | Worker 완료 | Job 7번·버전·문서 `INDEXED`, Chunk 5·ACTIVE Vector 5 |
| 16:06:06 | 최종 `DRY_RUN` | Issue 5번 `RESOLVED` |

## 해석 범위

- `STALLED_VERSION`은 같은 Issue 행을 유지한 채 Job 유무에 따라 심각도만 `WARNING ↔ ERROR`로 바뀌었다. 자동 복구는 끝까지 요청하지 않았다.
- 이 Issue는 Worker가 버전을 끝까지 처리한 뒤에야 닫혔다. 복구 Job을 만든 것만으로는 `updated_at`이 바뀌지 않으므로 Issue가 남는다.
- 정체는 실제 15분 대기가 아니라 `updated_at`을 과거로 옮겨 만들었다. 실제 Worker 지연이나 장애 원인을 재현한 것은 아니다.
- DB 컨테이너는 UTC, 앱은 Asia/Seoul `LocalDateTime`을 쓴다. 그래서 주입은 `now()`가 아니라 기존 값에서 20분을 뺐다.

## 증거

- 주입: [주입 1](inject-1-backdate.sql), [주입 1 출력](inject-1-output.txt), [주입 2](inject-2-delete-job.sql), [주입 2 출력](inject-2-output.txt), [실행 조건](conditions.txt), [업로드 응답](upload-response.json)
- 상태: [주입 전](state-0-before-injection.txt), [Job 삭제 뒤](state-2-after-job-delete.txt), [REPAIR 뒤](state-3-after-repair.txt), [Worker 완료 뒤](state-4-after-worker.txt), [Worker 켬 구간 변화](5-timeline-worker-on.txt)
- API: [Job 있을 때 탐지](1-detect-dry-run-live-job-batch1.json), [그때 OPEN Issue](1-issues-open.json), [Job 없을 때 탐지](2-detect-dry-run-no-job-batch1.json), [그때 OPEN Issue](2-issues-open.json), [REPAIR](3-repair-batch1.json), [재점검](4-recheck-dry-run-worker-off-batch1.json), [전체 Issue](4-issues-all.json)
