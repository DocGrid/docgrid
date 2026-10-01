# combined-01 — 세션 ID 보완 전 변경 관련 회귀

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:49:37 KST, 로컬 JVM·격리 PostgreSQL·Redis·HTTP·STOMP |
| 목적·성공 조건 | 변경 관련 단위·실제 통합 10개 클래스 전체 통과 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --tests '...PrimaryRoleQueryServiceTest' --tests '...StompDashboardOutboundAuthorizationInterceptorTest' --tests '...DashboardWebSocketControllerTest' --tests '...EmbeddingJobRetryServiceTest' --tests '...DashboardPushSchedulerTest' --tests '...RoleAuthorityServiceTest' --tests '...StompDashboardRealRoleRevocationIntegrationTest' --tests '...StompDashboardRoleRevocationIntegrationTest' --tests '...DashboardWebSocketIntegrationTest' --tests '...StompDestinationAuthorizationIntegrationTest' --rerun-tasks --quiet` |
| 결과 | **10개 클래스·48/48 통과**, 실패 0·오류 0·건너뜀 0 |
| 해석·한계 | 실제 발행 코드의 내부 판정 전달·비노출과 캐시 무효화 실패를 포함. GCP OpenProxy·부하 성능은 아직 시험하지 않음 |
