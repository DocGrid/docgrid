# 트랜잭션 경계 보완 재시험 — run-tx-boundary-db-02

| 항목 | 기록 |
| --- | --- |
| 목적 | 모델 실패 후보 조회의 지연 로딩 경계와 Worker의 실제 DB 완료 확인 |
| 실행 시각 | 2026-10-16 05:36 KST |
| 위치 | 로컬 PostgreSQL의 별도 시험 스키마와 백엔드 JVM |
| 명령 | `TEST_DB_SCHEMA=<격리 스키마> JWT_SECRET=<시험값> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.rag.service.RagFacadeTest' --tests 'com.opensource.docgrid.domain.rag.integration.RagJobWorkerIntegrationTest' --console=plain` |
| 성공 기준 | 단위 20개·DB 통합 1개 통과, 실패 0개 |
| 결과 | 21개 통과, 실패 0개. Gradle BUILD SUCCESSFUL (6초) |

모델 응답 대기 중 실제 DB 커넥션 수를 계측한 시험은 아니다. 수정 전후 A/B 경합과 커넥션 상태는 별도 실행으로 검증한다.
