# STOMP 권한 회수·진행 중 전송 경합 재현 — 로컬 실행 1

| 항목 | 내용 |
| --- | --- |
| 실행 ID | `stomp-revocation-dispatch-race-local-20261002-run01` |
| 목적 | 수신자별 primary 권한 확인이 끝난 뒤 실제 전송을 멈추고, 그 사이 HTTP 관리자 역할 회수가 200으로 끝나면 기존 진행 중 메시지가 클라이언트에 도착하는지 판정 |
| 위치 | 로컬 백엔드 통합 테스트·격리된 PostgreSQL/Redis 컨테이너. GCP/OpenProxy 시험 아님 |
| 코드 | `StompDashboardRealRoleRevocationIntegrationTest.inFlightMessage_canArriveAfterRevocationResponse_whenAuthorizationFinishedFirst` |
| 명령 | `DB_HOST=127.0.0.1 DB_PORT=55433 REDIS_PORT=6380 ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompDashboardRealRoleRevocationIntegrationTest' --console=plain` |
| 성공 기준 | 기존 새 push 차단 시험 통과. 새 경합 시험에서 일시정지 중 수신 0건, 실제 회수 HTTP 200·DB ADMIN 삭제, 해제 후 진행 중 메시지 1건 수신. 시험용 사용자·Redis 키와 격리 컨테이너 정리 |
| 상태 | **실패 — 환경 준비 단계**. 경합 코드 실행 전 Flyway 마이그레이션 중단 |

**판정 경계:** 이 시험의 수신 1건은 회수 응답 후 **새로 권한을 확인한** 메시지가 아니라, 응답 전 이미 허용됐지만 전송이 보류된 메시지다. 두 유형을 혼합해 “회수 후 신규 승인”이라고 주장하지 않는다.

## 01:02 KST 실행 결과

| 관측 | 수치·결과 | 해석 |
| --- | --- | --- |
| Gradle 테스트 작업 | 테스트 2개가 컨텍스트 로딩에서 실패, 경합 본문 실행 0건 | 테스트 결과로 경합을 판정할 수 없음 |
| 첫 원인 | Flyway `V32__convert_vector_columns_to_pgvector.sql`: PostgreSQL `vector` 타입 없음 | 새 격리 DB에 pgvector 확장을 활성화하지 않은 환경 준비 오류 |
| 재시도 계획 | 이 실행만을 위한 격리 DB에 `CREATE EXTENSION IF NOT EXISTS vector` 후 별도 실행 ID로 재시험 | 기존 로컬 DB·Redis의 데이터·설정과 GCP 3노드는 변경하지 않음 |
| 정리 상태 | 격리 컨테이너는 재시도 2~4 이후 정확한 두 이름으로 중지했고 자동 삭제 잔여 0개 확인 | Docker Desktop 시작 시 기존 Redis 컨테이너는 자동 기동됐으며 그대로 둠 |
