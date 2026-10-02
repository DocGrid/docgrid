# fix382-regression-r13 — 양방향 시험 포함 첫 종합 회귀

- 시각: 2026-10-02 **13:36:46~13:36:55 KST** (Gradle XML timestamp와 종료 로그 기준).
- 위치/기준: 로컬 전용 PostgreSQL 17·pgvector, Redis 7; `fix/382` 테스트 추가 작업 트리.
- 목적·성공 기준: 단위·Redis·기존 WebSocket·두 앱 양방향 STOMP 통합 테스트 전부 통과.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests <관련 7개 클래스> --rerun-tasks --no-daemon`.
- 관측: 컨트롤러 4 + 스케줄러 7 + 플래그 4 + 신호 단위 4 + 실제 Redis 1 + 기존 WebSocket 4 + 두 앱 양방향 STOMP 1 = **25/25 통과**, 실패 0, skip 0. Gradle 종료 코드 0.
- 해석: 기능 경계와 로컬 두 앱 실제 전달을 확인했다. 별도 GCP VM·내부 LB·OpenProxy 결과는 아니다.
- 정리: 두 Spring 앱 종료·시험 계정 삭제. 전용 PostgreSQL·Redis 컨테이너 2개 중지·자동 삭제를 확인했다. 기존 사용자 컨테이너 3개는 그대로 실행 중이다.
