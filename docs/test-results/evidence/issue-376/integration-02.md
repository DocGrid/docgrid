# integration-02 — 실제 회수·헤더 시험 재실행

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:45:55 KST, 로컬 격리 PostgreSQL·Redis·HTTP·STOMP |
| 목적·성공 조건 | integration-01의 동일한 3건에서 시험 입력을 보정해 모두 통과 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --tests '...StompDashboardRealRoleRevocationIntegrationTest' --rerun-tasks --quiet` |
| 결과 | **3/3 통과**, 실패 0·건너뜀 0. 회수 후 새 push 차단, 회수 전 허용 메시지의 늦은 수신, 내부 헤더 비노출 |
| 해석·한계 | 결정적 순서 구분이 유지됐다. 이후 Redis 실패 사례와 실제 발행 메서드 검사로 확장(redis-01·combined-01) |
