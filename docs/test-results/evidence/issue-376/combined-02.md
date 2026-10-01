# combined-02 — 세션 ID 경합 수정 후 첫 회귀

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:57:54 KST, 로컬 JVM·격리 PostgreSQL·Redis·HTTP·STOMP |
| 목적·성공 조건 | 후보 물리 세션 ID를 저장한 변경과 새 경합 시험을 포함해 관련 테스트를 재실행. 지정한 10개 클래스 전부 실행·통과가 목표 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] JWT_SECRET=[TEST_ONLY_VALUE] ./backend/gradlew -p backend test --tests '...PrimaryRoleQueryServiceTest' --tests '...StompDashboardOutboundAuthorizationInterceptorTest' --tests '...DashboardWebSocketControllerTest' --tests '...EmbeddingJobRetryServiceTest' --tests '...DashboardPushSchedulerTest' --tests '...RoleAuthorityServiceTest' --tests '...StompDashboardRealRoleRevocationIntegrationTest' --tests '...StompDashboardRoleRevocationIntegrationTest' --tests '...dashboard.integration.DashboardWebSocketIntegrationTest' --tests '...StompDestinationAuthorizationIntegrationTest' --rerun-tasks --quiet` |
| 결과 | Gradle 종료 코드 0. **9개 suite·45/45 통과**, 실패 0·오류 0·건너뜀 0. 지정한 `DashboardWebSocketIntegrationTest`의 package가 실제 `dashboard.websocket`인데 `dashboard.integration`으로 잘못 기입돼 1개 클래스가 실행되지 않음 |
| 해석·다음 조치 | 실행된 테스트는 통과했지만 10개 클래스라는 성공 기준에는 미달. 클래스 경로를 고친 combined-03을 별도로 실행했으며 이 결과를 최종 전체 회귀로 사용하지 않음 |
