# RAG claim·fencing DB 준비 실행 — run-claim-db-00

| 항목 | 기록 |
| --- | --- |
| 목적 | 토큰·lease SQL과 기존 RAG 동작 확인, heartbeat 단위 시험 추가 전 기준 |
| 실행 시각 | 2026-10-16 05:38 KST |
| 위치 | 로컬 PostgreSQL 별도 시험 스키마와 백엔드 JVM |
| 명령 | `TEST_DB_SCHEMA=<격리 스키마> JWT_SECRET=<시험값> ./backend/gradlew -p backend test --tests '...RagFacadeTest' --tests '...RagJobWorkerTest' --tests '...RagResponseCommandServiceTest' --tests '...RagResponseRepositoryTest' --tests '...RagResponseClaimIntegrationTest' --console=plain` |
| 결과 | 48개 통과, 실패 0개. Gradle BUILD SUCCESSFUL (7초) |

이후 실행 중인 claim의 heartbeat 단위 시험을 추가해 같은 묶음을 별도 run ID로 다시 실행했다.
