# fix382-websocket-r4 — WebSocket 회귀 준비 실패

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬, 기존 WebSocket 통합 테스트.
- 목적·성공 기준: 실제 STOMP CONNECT·SUBSCRIBE·전달 및 거부 4건 통과.
- 실행: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.websocket.DashboardWebSocketIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: **4건 모두 Spring context 생성 전에 실패**, PostgreSQL 연결 거절.
- 판정: DB 준비 실패로 코드 검증에는 사용할 수 없다. 전용 PostgreSQL을 띄워 재실행했다.
- 비밀/정리: 원시 예외의 로컬 호스트명은 공개 기록에 옮기지 않았다.
