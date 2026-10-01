# header-02 — 내부 헤더 전달·비노출 관문

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:40:47 KST, 로컬 격리 PostgreSQL 17+pgvector·Redis 7·실제 STOMP |
| 목적·성공 조건 | 서버 내부 헤더가 수신자별 outbound에 남고 클라이언트 STOMP 헤더에는 없는지 확인 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --tests '...StompDashboardRealRoleRevocationIntegrationTest.serverOnlyHeader_reachesOutboundButNotClient' --rerun-tasks --quiet` |
| 결과 | **1/1 통과**, 실패 0. 테스트 XML의 해당 케이스 시간 0.479초. 전송 헤더는 outbound에 있고 STOMP 프레임에는 없었음 |
| 해석·한계 | Spring 브로커 전달 관문 통과. 이 실행은 구현 전 수동 헤더를 사용했으므로 최종 변경의 실제 발행 경로는 combined-01에서 다시 검사 |
