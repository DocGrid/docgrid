# fix382-websocket-r5 — 테스트 DB 확장 누락

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬 전용 PostgreSQL 17 + pgvector 이미지.
- 목적·성공 기준: 기존 WebSocket 통합 테스트 4건 통과.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.websocket.DashboardWebSocketIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: Flyway `V32`에서 `type "vector" does not exist`; **4건 모두 context 생성 실패**.
- 판정: 이미지에는 확장 바이너리가 있지만 DB에 `CREATE EXTENSION vector`가 필요했다. 전용 DB에만 확장을 생성하고 재실행했다.
- 비밀/정리: 공개 기록에는 임시 포트·로컬 호스트명·원시 XML을 옮기지 않았다.
