# integration-01 — 내부 판정 누락으로 인한 시험 입력 실패

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:45:20 KST, 로컬 격리 PostgreSQL·Redis·HTTP·STOMP |
| 목적·성공 조건 | 실제 역할 회수·진행 중 메시지·서버 헤더 관문 3건 통과 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --tests '...StompDashboardRealRoleRevocationIntegrationTest' --rerun-tasks --quiet` |
| 결과 | **3건 중 2건 통과·1건 실패**. 헤더 관문 시험에서 직접 만든 MESSAGE에 새 필수 권한 snapshot이 없어 outbound가 메시지를 버림. 종료 코드 1 |
| 해석·수정 | fail-closed 구현은 의도대로였다. 시험 입력에 유효한 서버 내부 snapshot을 넣어 재실행. 뒤에는 수동 전송보다 실제 발행 메서드를 관측하도록 시험을 강화 |
