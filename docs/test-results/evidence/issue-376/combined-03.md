# combined-03 — 관련 10개 클래스 최종 회귀

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:58:41 KST, 로컬 JVM·격리 PostgreSQL·Redis·HTTP·STOMP |
| 목적·성공 조건 | 세션 ID 기반 후보 판정과 같은 사용자의 늦은 신규 세션 차단을 포함해 관련 10개 클래스 모두 통과 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] JWT_SECRET=[TEST_ONLY_VALUE] ./backend/gradlew -p backend test --tests '...PrimaryRoleQueryServiceTest' --tests '...StompDashboardOutboundAuthorizationInterceptorTest' --tests '...DashboardWebSocketControllerTest' --tests '...EmbeddingJobRetryServiceTest' --tests '...DashboardPushSchedulerTest' --tests '...RoleAuthorityServiceTest' --tests '...StompDashboardRealRoleRevocationIntegrationTest' --tests '...StompDashboardRoleRevocationIntegrationTest' --tests '...dashboard.websocket.DashboardWebSocketIntegrationTest' --tests '...StompDestinationAuthorizationIntegrationTest' --rerun-tasks --quiet` |
| 결과 | Gradle 종료 코드 0. **10개 suite·49/49 통과**, 실패 0·오류 0·건너뜀 0 |
| 해석·한계 | 실제 HTTP 역할 회수·Redis·DB·STOMP와 늦은 신규 세션 경계가 로컬에서 통과. 분산 백엔드·OpenProxy·고부하의 실측은 아님 |
