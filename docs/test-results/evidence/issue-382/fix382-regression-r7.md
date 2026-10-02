# fix382-regression-r7 — 최종 Java 선택 회귀

- 시각: 2026-10-02 **13:24:28~13:24:35 KST** (Gradle XML timestamp와 종료 로그 기준).
- 위치/기준: 로컬 전용 PostgreSQL 17·pgvector와 Redis 7, 코드 `d751836`·테스트 `80488ac`에 해당하는 작업 트리.
- 목적·성공 기준: Redis 두 연결의 양방향·재구독, 로컬/원격 플래그, primary 인가 및 기존 STOMP 회귀 모두 통과.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests <관련 6개 클래스> --rerun-tasks --no-daemon`.
- 관측: 컨트롤러 4, 스케줄러 7, 플래그 4, 신호 단위 4, 실제 Redis 통합 1, WebSocket 통합 4 = **24/24 통과**, 실패 0, skip 0, Gradle 종료 코드 0.
- 해석: 두 Redis 클라이언트 사이 신호와 기존 단일 JVM STOMP는 확인했다. GCP의 별도 JVM·내부 LB·OpenProxy 수신까지 이 숫자로 주장하지 않는다.
- 정리: 이 작업에서 만든 전용 DB·Redis 컨테이너 2개를 중지했고 `--rm`으로 제거했다. 기존 사용자 컨테이너는 건드리지 않았다.
