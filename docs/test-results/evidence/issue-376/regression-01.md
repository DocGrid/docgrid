# regression-01 — 기존 WebSocket 경계

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:47:46 KST, 로컬 격리 PostgreSQL·Redis·STOMP |
| 목적·성공 조건 | 기존 관리자 구독, 대시보드 push, 목적지별 인가가 회귀 없이 통과 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --tests '...StompDashboardRoleRevocationIntegrationTest' --tests '...DashboardWebSocketIntegrationTest' --tests '...StompDestinationAuthorizationIntegrationTest' --rerun-tasks --quiet` |
| 결과 | **12/12 통과**(3+4+5), 실패 0·건너뜀 0 |
| 해석·한계 | 모의 역할 변경 기반 시험은 새 primary batch 모의 결과도 같은 역할 상태로 맞췄다. 실제 DB 회수는 redis-01·combined-01에서 별도 확인 |
