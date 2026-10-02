# fix382-websocket-r6 — WebSocket 회귀 통과

- 시각: 2026-10-02 **13:19 KST** (종료 로그 기준).
- 위치/기준: 로컬 전용 PostgreSQL 17, `CREATE EXTENSION IF NOT EXISTS vector` 실행 후.
- 목적·성공 기준: 기존 실제 STOMP WebSocket 통합 테스트 4건 모두 통과.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.websocket.DashboardWebSocketIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: **4/4 통과**, Gradle 종료 코드 0.
- 해석: 기존 단일 JVM의 CONNECT·SUBSCRIBE·전달·거부 동작 회귀는 없었다. 원격 B 수신은 별도 문제다.
- 정리: 전용 DB는 최종 종합 run까지 유지한 뒤 중지·자동 삭제했다.
