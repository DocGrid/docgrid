# RAG claim·fencing DB 시험 — run-claim-db-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 유효한 lease 유지, 만료 재선점, 이전 token 완료 거부와 기존 RAG 동작 회귀 확인 |
| 실행 시각 | 2026-10-16 05:39 KST |
| 위치 | 로컬 PostgreSQL 별도 시험 스키마와 백엔드 JVM |
| 명령 | `TEST_DB_SCHEMA=<격리 스키마> JWT_SECRET=<시험값> ./backend/gradlew -p backend test --tests '...RagFacadeTest' --tests '...RagJobWorkerTest' --tests '...RagResponseCommandServiceTest' --tests '...RagResponseRepositoryTest' --tests '...RagResponseClaimIntegrationTest' --console=plain` |
| 성공 기준 | 다섯 시험 묶음 전부 통과, 실패 0개 |
| 결과 | 49개 통과, 실패 0개. Gradle BUILD SUCCESSFUL (7초) |

준비 실행 1회는 `JWT_SECRET` 시험 환경값 누락으로 Spring 컨텍스트 시작 전에 실패했다(10개 중 1개 초기화 오류). 시험값을 설정해 같은 목적의 실행을 새로 수행했고 위 결과를 얻었다. 이 DB 시험은 두 실제 JVM 기동·중단의 시간 순서를 아직 검증하지 않는다.
