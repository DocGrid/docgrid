# full-03 — 최종 코드 전체 Gradle 시험

| 항목 | 기록 |
| --- | --- |
| 종료 시각·위치 | 2026-10-02 02:00:05 KST, 로컬 JVM·격리 PostgreSQL·Redis |
| 목적·성공 조건 | 세션 ID 경합 보완 후 전체 테스트 실패·오류·건너뜀 0건 |
| 실행 | `DB_PORT=[LOCAL_TEST_PORT] REDIS_PORT=[LOCAL_TEST_PORT] TEST_DB_SCHEMA=[TEST_SCHEMA] JWT_SECRET=[TEST_ONLY_VALUE] ./backend/gradlew -p backend test --rerun-tasks --quiet` |
| 결과 | Gradle 종료 코드 0. **210개 suite·1,271/1,271 통과**, 실패 0·오류 0·건너뜀 0 |
| 해석·한계 | 최종 코드의 로컬 전체 회귀 통과. GCP 3노드/OpenProxy 부하 성능과 장애 전환의 증거는 아니며 별도 Test PR에서 같은 조건으로 측정해야 함 |
| 정리 | 이번 시험용 격리 PostgreSQL·Redis 컨테이너 두 개만 `docker stop`으로 종료. 이어서 `docker ps -a --filter name=docgrid-stomp-batch-376` 결과가 0줄임을 확인. 기존 GCP 부하 환경은 변경하지 않음 |
