# RAG claim DB 준비 실행 — run-claim-db-preliminary-46

| 항목 | 기록 |
| --- | --- |
| 목적 | SQL 소유권 조건 도입 직후 관련 단위·통합 시험 확인 |
| 실행 시각 | 2026-10-16 05:35 KST |
| 위치 | 로컬 PostgreSQL 별도 시험 스키마와 백엔드 JVM |
| 명령 | `TEST_DB_SCHEMA=<격리 스키마> JWT_SECRET=<시험값> ./backend/gradlew -p backend test --tests '...RagFacadeTest' --tests '...RagJobWorkerTest' --tests '...RagResponseCommandServiceTest' --tests '...RagResponseRepositoryTest' --tests '...RagResponseClaimIntegrationTest' --console=plain` |
| 결과 | 46개 통과, 실패 0개. Gradle BUILD SUCCESSFUL (7초) |

이후 만료·연장·heartbeat 시험을 추가해 같은 목적의 후속 실행을 별도 기록했다.
