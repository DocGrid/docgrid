# fix382-ab-r12 — A→B 및 B→A 실제 STOMP 전달

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬 독립 Spring context 2개·각자 SimpleBroker, 전용 PostgreSQL 17/pgvector·Redis 7.
- 목적·성공 기준: 어느 쪽만 발행해도 반대편 관리자 구독이 실제 MESSAGE를 받고 자기 DB 요약을 전달.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeWebSocketIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: **1/1 통과**, 시험 내부에서 **A→B 1건·B→A 1건** 수신을 단언했다. 양쪽 수신 요약은 발행 측 합성 `total=999999`가 아니라 자기 DB 계산 결과와 같았다.
- 해석: 대칭 경로를 로컬 두 앱에서 확인했다. Redis 장애·GCP 네트워크는 이 시험에 없다.
- 정리: 시험 계정 삭제, STOMP 연결 해제, 두 앱 종료. 전용 컨테이너는 최종 종합 run까지 유지했다.
