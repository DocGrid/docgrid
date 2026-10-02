# fix382-ab-r9 — 두 앱 시험 컴파일 실패

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬 전용 PostgreSQL 17·Redis 7, 두 Spring 앱 통합 테스트 초안.
- 목적·성공 기준: A 발행 → B의 실제 STOMP 관리자 구독자가 자기 DB 요약 수신.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeWebSocketIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: `connectAsync`의 두 Java 오버로드가 `null` 인자를 함께 받을 수 있어 **컴파일 실패, 시험 실행 0건**.
- 판정/조치: WebSocket HTTP 헤더 인자를 명시적으로 캐스팅해 모호성을 없앴다. 성공으로 세지 않았다.
- 정리: 전용 컨테이너는 재시험을 위해 유지했다가 최종 run 후 제거했다.
