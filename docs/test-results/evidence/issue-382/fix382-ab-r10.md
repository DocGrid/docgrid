# fix382-ab-r10 — Spring 프로필 중복으로 기동 실패

- 시각: 2026-10-02 **13:34 KST** (실패 로그 기준).
- 위치/기준: 로컬 전용 PostgreSQL 17·Redis 7, 두 Spring 앱 통합 테스트.
- 목적·성공 기준: 두 앱이 동일한 test 스키마에서 시작하고 A→B 메시지를 실제 수신.
- 실행: `DB_PORT=<임시포트> DB_NAME=docgrid DOCGRID_TEST_REDIS_PORT=<임시포트> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.dashboard.event.DashboardCrossNodeWebSocketIntegrationTest' --rerun-tasks --no-daemon`.
- 관측: Flyway는 test 스키마에 **45개 migration 적용**. 그러나 `application.yml`의 기본 local 프로필이 builder의 test 프로필과 함께 활성화돼 DataSource는 `public` 스키마를 보았고, JPA가 `collection_documents` 테이블이 없다고 보고했다. 시험 **1건 실패**.
- 판정/조치: 앱 실행 인자 `--spring.profiles.active=test`로 프로필을 단일화했다. 코드 기능 실패로 해석하지 않았다.
- 정리: 전용 컨테이너는 재시험을 위해 유지했다가 최종 run 후 제거했다.
