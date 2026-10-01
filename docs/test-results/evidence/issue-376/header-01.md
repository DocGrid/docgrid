# header-01 — Gradle 실행 전 권한 오류

| 항목 | 기록 |
| --- | --- |
| 시각·위치 | 2026-10-02 KST, 로컬 작업 트리·격리 PostgreSQL/Redis. 정확한 분·초는 별도 수집하지 못함 |
| 목적·성공 조건 | 내부 헤더가 outbound에는 남고 클라이언트 STOMP에는 보이지 않는지 1건 통과 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --tests '...StompDashboardRealRoleRevocationIntegrationTest.serverOnlyHeader_reachesOutboundButNotClient' --rerun-tasks --quiet` |
| 결과 | 테스트 본문 **0건**. Gradle wrapper 캐시의 잠금 파일 접근이 샌드박스에서 거부됨. 종료 코드 1 |
| 해석·다음 단계 | 기능 실패가 아니다. 동일 컨테이너와 코드에서 Gradle 캐시 접근 권한만 허용해 재실행(header-02) |
