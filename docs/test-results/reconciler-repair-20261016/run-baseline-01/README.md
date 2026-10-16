# 기준선 점검 — run-baseline-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 주입 전 깨끗한 상태에서 Reconciler가 Issue를 만들지 않는지 확인 |
| 시각 | 2026-10-16 15:57:23~15:57:24 KST |
| 환경 | 1단계 Backend(Worker·Dispatcher 켬), 시드 문서 2건만 있는 새 DB |
| 방법 | `POST /admin/sync/reconcile` `DRY_RUN`, cursor 0부터 `hasMore=false`까지 반복 |
| 성공 기준 | 탐지 0건, Issue 0건, 실행 이력 `COMPLETED` |
| 결과 | 통과. 배치 1회, 버전 **2개** 점검, 탐지 **0건**, 복구 요청 0건, Issue 0건 |
| 정리 | 시험 DB는 전체 시험 뒤 컨테이너째 삭제 |

- 실행 ID: `893413a4-17af-4f48-b371-5dcaa2f82914`
- 시드 문서 2건은 Flyway `R__seed_test_fixtures`가 넣은 개발용 고정 Vector 문서다. Chunk 2개·ACTIVE Vector 2개로 개수가 맞는다.
- `DRY_RUN`도 `sync_reconciliation_runs` 1행과 관리자 감사 Action 1행을 기록했다. 읽기 전용 모드가 아니다.

## 증거

- [점검 응답](dry-run-batch1.json)
- [점검 전 전역 상태](global-before.txt), [점검 후 전역 상태](global-after.txt)
- [Issue 목록](issues-after.json), [운영 요약](summary-after.json)
