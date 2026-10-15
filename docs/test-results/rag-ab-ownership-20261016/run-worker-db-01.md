# RAG Worker DB 확정 시험 — run-worker-db-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 선점한 작업이 별도 스레드와 트랜잭션을 거쳐 DB 최종 상태로 확정되는지 확인 |
| 실행 시각 | 2026-10-16 05:40 KST |
| 위치 | 로컬 PostgreSQL 별도 시험 스키마와 백엔드 JVM |
| 명령 | `TEST_DB_SCHEMA=<격리 스키마> JWT_SECRET=<시험값> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.rag.integration.RagJobWorkerIntegrationTest' --console=plain` |
| 성공 기준 | 최종 상태가 `PROCESSING`이 아니고 답변이 저장됨 |
| 결과 | 1개 통과, 실패 0개. Gradle BUILD SUCCESSFUL (5초) |

이 통합 시험은 모델 응답의 의미 품질을 판정하지 않으며, 두 JVM의 동시 경합도 별도 실행에서 검증한다.
