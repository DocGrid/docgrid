# RAG Worker DB 준비 실행 — run-worker-db-preliminary

| 항목 | 기록 |
| --- | --- |
| 목적 | 소유권 API 정리 전 실제 DB 완료 동작 확인 |
| 실행 시각 | 2026-10-16 05:35 KST |
| 위치 | 로컬 PostgreSQL 별도 시험 스키마와 백엔드 JVM |
| 명령 | `TEST_DB_SCHEMA=<격리 스키마> JWT_SECRET=<시험값> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.rag.integration.RagJobWorkerIntegrationTest' --console=plain` |
| 결과 | 1개 통과, 실패 0개. Gradle BUILD SUCCESSFUL (5초) |

이후 소유권 쓰기 API를 정리했으므로 같은 목적을 `run-worker-db-01`로 재실행했다.
