# full-01 — 필수 JWT 환경값 누락 상태의 전체 시험

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 01:50:59 KST, 로컬 JVM·격리 PostgreSQL·Redis |
| 목적·성공 조건 | 기본 Gradle 전체 테스트 통과 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] ./backend/gradlew -p backend test --rerun-tasks --quiet` |
| 결과 | **1,251건 실행·24건 실패**. 해당 14개 suite의 Spring Context 생성 실패 경로에서 `JWT_SECRET` placeholder 누락을 확인. 종료 코드 1 |
| 해석·수정 | 작업 트리에 `.env`가 없어 필요한 값을 제공하지 못한 시험 환경 오류. 실제 운영 비밀 대신 격리 시험 전용 값을 환경 변수로 설정해 full-02로 재실행. 실패 건수·첫 실행은 삭제하지 않음 |
