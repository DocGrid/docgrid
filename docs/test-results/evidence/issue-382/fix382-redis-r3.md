# fix382-redis-r3 — 실제 Redis 신호 통합 시험

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬 전용 Redis 7 컨테이너, `fix/382` 초기 테스트 코드.
- 목적·성공 기준: A→B·B→A 신호, 자기 echo 무시, B 재구독 갱신.
- 실행: `DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeSignalRedisIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: **1/1 통과**, skip 0. 이후 A/B에 별도 Redis 연결을 주는 강화된 테스트를 최종 run에서 다시 실행했다.
- 해석: 실제 Pub/Sub 경로 통과이나 Spring Boot 앱 두 대의 STOMP 전달 시험은 아니다.
- 정리: 이 run 전용 Redis 컨테이너를 중지·자동 삭제; 기존 사용자 컨테이너는 유지.
