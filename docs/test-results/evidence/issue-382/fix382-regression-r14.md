# fix382-regression-r14 — 조용한 기준선을 포함한 최종 회귀

- 시각: 2026-10-02 **13:39:15 KST** (종료 로그 기준; 시작 초 단위는 별도 기록하지 못함).
- 위치/기준: 로컬 전용 PostgreSQL 17·pgvector, Redis 7; 프로덕션 `d751836`, 테스트 `80488ac`·`3279ae9`.
- 목적·성공 기준: 두 앱의 초기 Redis 재구독 새로고침을 비운 뒤 **추가 A/B 프레임이 없는 조용한 구간**을 확인하고, A→B·B→A 각각 발행 후 실제 관리자 STOMP 수신을 증명한다. 기존 대시보드 관련 시험도 모두 통과해야 한다.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests <관련 7개 클래스> --rerun-tasks --no-daemon`.
- 관측: Java 선택 회귀 **25/25 통과**, 실패 0, skip 0, Gradle 종료 코드 0. 양방향 시험 내부에서 발행 전 조용한 구간과 A→B/B→A 실제 수신·독립 DB 요약을 각각 단언했다.
- 해석: 초기 구독 callback 프레임을 실제 교차 노드 전달로 오인할 가능성을 낮춘 로컬 두 앱 결과다. GCP 별도 VM 실측은 아니다.
- 정리: 시험 계정 삭제·두 Spring 앱 종료 후 전용 PostgreSQL·Redis 컨테이너를 중지·자동 삭제했다. 기존 사용자 컨테이너는 유지했다.
