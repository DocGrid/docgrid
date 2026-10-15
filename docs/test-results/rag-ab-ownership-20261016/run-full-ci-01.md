# 전체 CI 1차 실패 — run-full-ci-01

| 항목 | 기록 |
| --- | --- |
| 목적 | claim·lease 변경의 전체 백엔드·프런트엔드 회귀 검사 |
| 시각·위치 | 2026-10-16 05:51~05:55 KST, GitHub Actions PR #515 |
| 버전·방법 | `97037f3`, 백엔드 `./backend/gradlew -p backend build --no-daemon`, 프런트엔드 CI lint·build·test |
| 성공 기준 | 모든 CI job 성공, 백엔드 테스트 실패 0개 |
| 결과 | 프런트엔드 성공. 백엔드 **1,495개 중 2개 실패**, 5개 건너뜀. 전체 CI 실패 |

실패 두 건은 이번 PR에 포함된 시험에서 발생했다. `RagResponseRepositoryTest`의 lease 시각 비교는 PostgreSQL이 나노초를 마이크로초로 저장하는 정밀도 차이였다. `RagResponseClaimIntegrationTest`는 백그라운드 RAG Worker가 시험 행을 먼저 소비할 수 있어 잠금 경계가 불안정했다. 전자는 시험 시각을 마이크로초로 자르고, 후자는 해당 통합 시험에서만 Worker를 mock으로 대체했다. 수정 후 별도 격리 스키마에서 재시험했다.

[CI 실행](https://github.com/DocGrid/docgrid/actions/runs/38805265225) · 이 회차는 실패한 실행으로 남겨 둔다.
