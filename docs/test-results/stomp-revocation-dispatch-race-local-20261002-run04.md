# STOMP 권한 회수·진행 중 전송 경합 재현 — 로컬 실행 4

| 항목 | 내용 |
| --- | --- |
| 실행 ID | `stomp-revocation-dispatch-race-local-20261002-run04` |
| 목적 | [실행 2](stomp-revocation-dispatch-race-local-20261002-run02.md)·[실행 3](stomp-revocation-dispatch-race-local-20261002-run03.md)에 이어 경합 결과의 세 번째 성공 재현 확인 |
| 위치 | 로컬 격리 PostgreSQL/Redis 및 백엔드 통합 테스트. GCP 시험 아님 |
| 명령 | `DB_HOST=127.0.0.1 DB_PORT=55433 REDIS_PORT=6380 ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompDashboardRealRoleRevocationIntegrationTest' --rerun-tasks --console=plain` |
| 성공 기준 | 두 테스트 통과, 실제 회수 200·역할 삭제 이후 보류 중 메시지 수신 재현. 시험 전용 데이터·격리 컨테이너 정리 |
| 상태 | **통과** — 2026-10-02 01:04:54 KST 시작, Gradle `BUILD SUCCESSFUL` |

| 관측 | 수치·결과 | 해석 |
| --- | --- | --- |
| JUnit XML을 종료 직후 확인 | 테스트 2개, 실패 0, 오류 0, 건너뜀 0, 실행 1.881초 | 기존 차단 시험과 진행 중 전송 경합 재현이 세 번째로 통과 |
| 경합 테스트의 필수 순서 | 보류 중 수신 0건 → 회수 HTTP 200·DB 역할 삭제 → 해제 뒤 `documents.total=42` 메시지 1건 수신 | 반복 가능한 진행 중 메시지 수신 경계 확인 |
| 시험 데이터 | 해당 패턴의 DB 사용자 0건, 격리 Redis 역할 키 0건 | 테스트가 만든 사용자·캐시 데이터 잔여 없음 |
| 격리 컨테이너 | 정확한 두 이름으로 `docker stop` 후 `docker ps -a` 잔여 **0개** | 자동 삭제 완료, 기존 로컬 DB·Redis와 GCP 노드 무변경 |
