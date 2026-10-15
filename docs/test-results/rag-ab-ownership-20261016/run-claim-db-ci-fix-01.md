# CI 실패 조건 수정 후 DB 재시험 — run-claim-db-ci-fix-01

| 항목 | 기록 |
| --- | --- |
| 목적 | CI에서 실패한 행 잠금·lease 시각 비교 두 시험을 수정 후 검증 |
| 시각·위치 | 2026-10-16 05:57 KST, 로컬 PostgreSQL 별도 시험 스키마와 백엔드 JVM |
| 명령 | `TEST_DB_SCHEMA=<격리 스키마> JWT_SECRET=<시험값> ./backend/gradlew -p backend test --tests '...RagResponseClaimIntegrationTest' --tests '...RagResponseRepositoryTest' --console=plain` |
| 성공 기준 | 두 클래스의 모든 시험 통과, 실패 0개 |
| 결과 | **13개 통과, 실패·건너뜀 0개**, `BUILD SUCCESSFUL`(8초) |
| 정리 | 이 실행의 격리 스키마 삭제 확인 |

이 결과는 CI의 전체 1,495개 회귀 시험을 대신하지 않는다. 수정 커밋에 대해 GitHub Actions 전체 빌드를 다시 확인한다.
