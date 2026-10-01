# full-02 — 시험 전용 JWT 설정 후 전체 재실행

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:52:55 KST, 로컬 JVM·격리 PostgreSQL·Redis |
| 목적·성공 조건 | 필요한 테스트 환경값을 채운 뒤 기본 Gradle 전체 시험 실패·오류·건너뜀 0건 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] JWT_SECRET=[TEST_ONLY_VALUE] ./backend/gradlew -p backend test --rerun-tasks --quiet` |
| 결과 | **210개 suite·1,270/1,270 통과**, 실패 0·오류 0·건너뜀 0. Gradle 종료 코드 0 |
| 해석·한계 | full-01의 필수 설정 누락을 보완하자 기본 전체 시험이 통과했다. 이 값은 시험 전용이며 실제 운영 JWT는 사용하지 않았다. GCP 부하·OpenProxy 장애 시험의 대체 근거는 아니다 |
