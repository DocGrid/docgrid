# 전체 CI 2차 실패 — run-full-ci-02

| 항목 | 기록 |
| --- | --- |
| 목적 | 첫 CI의 행 잠금·시간 정밀도 실패 수정 뒤 전체 회귀 재확인 |
| 시각·위치 | 2026-10-16 05:58~06:01 KST, GitHub Actions PR #515 |
| 버전·방법 | `dbeefd4`, 백엔드 `./backend/gradlew -p backend build --no-daemon`, 프런트엔드 CI lint·build·test |
| 성공 기준 | 모든 CI job 성공, 백엔드 실패 0개 |
| 결과 | 프런트엔드 성공. 백엔드 **1,495개 중 1개 실패**, 5개 건너뜀. 전체 CI 실패 |

행 잠금 통합 시험의 경합은 해소됐다. 남은 `RagResponseRepositoryTest.renewClaimLease_requiresCurrentOwnerAndToken`은 여전히 나노초와 마이크로초 차이로 실패했다. 첫 수정에서 같은 파일의 다른 `now` 선언을 바꾼 실수를 확인했다. 해당 메서드의 시각만 잘라내고, 잘못 바꾼 부분은 원복해 다시 검증한다.

[CI 실행](https://github.com/DocGrid/docgrid/actions/runs/38806250513) · 이 회차도 실패한 실행으로 남겨 둔다.
