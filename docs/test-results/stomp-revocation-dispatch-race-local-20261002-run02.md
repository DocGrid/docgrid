# STOMP 권한 회수·진행 중 전송 경합 재현 — 로컬 실행 2

| 항목 | 내용 |
| --- | --- |
| 실행 ID | `stomp-revocation-dispatch-race-local-20261002-run02` |
| 목적 | [첫 실행](stomp-revocation-dispatch-race-local-20261002-run01.md)의 격리 DB 확장 누락을 바로잡고 실제 HTTP 회수·WebSocket 전송 경합을 판정 |
| 위치 | 로컬 백엔드 통합 테스트·격리 PostgreSQL/Redis. GCP/OpenProxy 시험 아님 |
| 사전 조건 | 격리 PostgreSQL DB의 pgvector 확장 활성화, 기존 로컬 DB·Redis 무변경 |
| 명령 | `DB_HOST=127.0.0.1 DB_PORT=55433 REDIS_PORT=6380 ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompDashboardRealRoleRevocationIntegrationTest' --console=plain` |
| 성공 기준 | 두 테스트 통과. 새 경합 시험에서는 권한 확인 이후 전송 보류 → HTTP 200·ADMIN 삭제 → 전송 해제 후 메시지 수신 순서가 관측되어야 함 |
| 상태 | **통과** — 2026-10-02 01:03 KST, Gradle `BUILD SUCCESSFUL` |

## 관측 결과

| 구분 | 숫자·상태 | 해석 |
| --- | --- | --- |
| JUnit XML | 테스트 2개, 실패 0, 오류 0, 건너뜀 0, 실행 1.887초 | 기존 회수 후 새 push 차단과 새 진행 중 전송 경합이 함께 통과 |
| 일시정지 중 | 메시지 수신 0건 | 실제 outbound 권한 검사 종료 뒤, 다음 전송 단계를 잠금 |
| HTTP 회수 | 200 OK, DB ADMIN 매핑 없음 | 메시지 전송 잠금 상태에서 실제 DB 커밋 완료 |
| 잠금 해제 뒤 | 기존 진행 중 메시지 1건 수신, 시험 메시지 `documents.total=42` | **회수 응답 후 수신**은 재현. 회수 뒤 새로 권한을 허용한 메시지라는 뜻은 아님 |
| 정리 상태 | 시험 전용 사용자·Redis 키는 테스트 `@AfterEach`가 정리. 격리 컨테이너는 반복 시험 후 중지·자동 삭제, 잔여 0개 확인 | 사용자 데이터와 반복 시험 인프라의 정리 단계를 구분 |
