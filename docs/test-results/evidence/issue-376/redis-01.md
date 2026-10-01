# redis-01 — 무효화 실패에도 이전 ADMIN 우회 없음

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:46:52 KST, 로컬 격리 PostgreSQL·Redis·HTTP·STOMP |
| 목적·성공 조건 | Redis 역할 무효화 Lua 호출 실패 → DB 회수 200 → 이전 ADMIN 캐시 잔존 → 새 push 수신 0건 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --tests '...StompDashboardRealRoleRevocationIntegrationTest' --rerun-tasks --quiet` |
| 결과 | 클래스 **4/4 통과**. 실패를 주입한 테스트에서 HTTP 200, DB ADMIN 없음, Redis `ADMIN` 캐시 잔존, 새 대시보드 프레임 수신 0건, 기존 세션 종료 확인 |
| 해석·한계 | WebSocket 발행은 Redis 캐시를 신뢰하지 않았다. Redis 전체 서버 다운이나 GCP failover 주입은 아니다. 시험용 계정·키는 `@AfterEach`에서 삭제 |
