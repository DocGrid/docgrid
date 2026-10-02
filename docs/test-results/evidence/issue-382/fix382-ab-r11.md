# fix382-ab-r11 — A→B 실제 STOMP 전달

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬의 독립 Spring context 2개·각자 SimpleBroker, 전용 PostgreSQL 17/pgvector·Redis 7.
- 목적·성공 기준: A만 합성 발행했을 때 B 관리자 구독이 실제 MESSAGE를 받고 B가 DB 요약을 독립 계산.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeWebSocketIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: **1/1 통과**, Gradle 종료 코드 0. B의 수신 `documents`는 B가 계산한 DB 요약과 같고 A의 합성 `total=999999`와 달랐다.
- 해석: 로컬 두 앱 사이의 실제 WebSocket 경로는 통과했다. GCP 별도 VM/LB/OpenProxy 측정으로 확대 해석하지 않는다.
- 정리: 전용 DB 시험 사용자·역할 연결을 삭제하고 두 Spring context를 닫았다.
