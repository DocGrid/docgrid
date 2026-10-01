# 대시보드 WebSocket primary 일괄 인가 로컬 검증 — 이슈 #376

> 2026-10-02 KST. 범위는 로컬 격리 PostgreSQL 17+pgvector, Redis 7, 실제 HTTP/STOMP 통합 시험이다. GCP 3노드/OpenProxy 성능·장애 검증은 수행하지 않았다. 계정·호스트명·내부 주소·서명 문자열은 결과 파일에 기록하지 않았다.

## 문제와 변경

기존에는 대시보드 push의 물리 수신 세션마다 `pg_is_in_recovery()`와 역할 조회를 수행했다. [#374 측정](gimin-374-stomp-dashboard-outbound-load-20261001.md)의 같은 ADMIN 50세션 조건에서는 수신 지연 p95 중앙값 2,443.7ms와 30초 수신 588건이 관측됐다. 이 결과는 새 변경의 성능 향상을 증명하지 않는다.

이번 변경은 한 push의 열린 CONNECT-ADMIN 세션에서 사용자 ID를 중복 제거한 다음, 현재 primary에서 ADMIN 사용자 ID를 일괄 조회한다. 후보의 물리 세션 ID→사용자 ID 대응과 확인된 ADMIN 집합을 불변 서버 내부 헤더로 브로커에 전달하고, outbound는 실제 수신 세션 ID와 그 결과를 대조한다. 조회 실패·standby·헤더 누락은 이전 CONNECT 역할이나 Redis 캐시로 우회하지 않는다. 판정 당시 없던 새 세션은 **같은 사용자의 다른 세션이 허용됐더라도** 이번 메시지만 버리고, 후보였지만 ADMIN이 아닌 세션은 버리고 닫는다.

역할 회수 HTTP 200 **이후 시작한** 새 primary 판정에서 이전 ADMIN을 승인하지 않는 것이 완료 기준이다. 회수 전에 이미 판정을 마친 메시지는 HTTP 200 뒤 늦게 수신될 수 있으므로 별도 분류한다. 이는 클라이언트 수신 기준 0건 보장이 아니다. 비동기 failover에서 회수 커밋 자체가 남는지(RPO)도 이 시험 범위 밖이다.

## 변경 경계

| 코드 | 역할 |
| --- | --- |
| `PrimaryRoleQueryService`, `UserRoleRepository` | read-write 트랜잭션에서 primary를 확인하고 후보 ADMIN ID를 최대 500명씩 조회 |
| `DashboardWebSocketController` | 요약을 받은 뒤 사용자 ID 중복 제거 → primary 판정 → 비-native 서버 내부 헤더로 발행. 후보 0명은 DB 조회 생략 |
| `DashboardAuthorizationSnapshot`, `StompDashboardOutboundAuthorizationInterceptor` | 이 push의 물리 세션 후보와 허용 사용자 집합을 실제 세션과 대조. 헤더 누락·비허용은 fail-closed |
| `EmbeddingJobRetryService` | 알림 발행 실패가 이미 커밋된 재처리 성공 응답을 실패로 뒤집지 않음. 스케줄러는 기존대로 dirty 신호를 되살려 재시도 |
| 단위·통합 테스트 | 실제 HTTP 회수·Redis 캐시·PostgreSQL·WebSocket, 내부 헤더 비노출, 실패 경로와 기존 RAG/목적지 경계 확인 |

## 실행 결과

각 실행은 [개별 한국어 로그](evidence/issue-376/README.md)에 분리했다. Gradle의 JUnit XML은 동일 경로가 재실행 때 덮어써지고 로컬 호스트명·개발 환경 출력이 포함될 수 있어 원문을 공개하지 않았다. 아래 숫자는 실행 직후 결과 또는 종료 코드와 XML의 suite 합계를 읽어 기록했다.

| 실행 | 목적 | 결과 | 해석 |
| --- | --- | --- | --- |
| header-01/02 | 내부 헤더 전달·비노출 관문 | 첫 시도는 Gradle 캐시 접근 거부로 본문 미실행, 재실행 1/1 통과 | 권한을 달리해 실행한 뒤 실제 브로커·STOMP 프레임 확인 |
| unit-01 | 일괄 판정·실패 정책 단위 시험 | 21/21 통과 | 구현 중간 결과. 이후 테스트가 추가되어 최종 수치는 아래 48건에 포함 |
| integration-01/02 | 실제 회수·경합·헤더 시험 | 첫 실행 3건 중 1건 실패, 입력에 필수 인가 snapshot을 넣은 뒤 3/3 통과 | 첫 실패는 fail-closed가 의도대로 작동한 시험 입력 오류 |
| redis-01 | Redis 무효화 실패에도 새 push 차단 | 4/4 통과 | 실제 DB 회수 후 낡은 Redis `ADMIN`이 남아도 primary 판정으로 차단 |
| regression-01 | 기존 WebSocket 경계 | 12/12 통과 | 기존 구독·RAG 목적지 규칙 회귀 없음 |
| combined-01 | 변경 관련 10개 클래스 | 48/48 통과 | 단위와 실제 HTTP·DB·Redis·STOMP 통합 결과 |
| full-01/02 | 기본 Gradle 전체 시험 | 첫 실행 1,251건 중 24건 실패, 시험용 `JWT_SECRET` 지정 후 1,270/1,270 통과 | 첫 실패는 필수 환경값 누락으로 인한 컨텍스트 생성 실패. 실제 비밀은 사용하지 않음 |
| combined-02/03 | 같은 사용자·새 세션 경합 수정 후 회귀 | 첫 실행은 경로 오타로 9개 클래스·45/45만 실행, 정확한 경로로 10개 클래스·49/49 통과 | 늦게 연결된 같은 사용자 세션도 이번 push에서 제외하는 회귀 시험 포함 |
| full-03 | 최종 코드의 전체 Gradle 시험 | 210개 suite·1,271/1,271 통과 | 최종 코드의 로컬 전체 회귀 결과. GCP 부하·장애 검증은 별도 |

## 주요 판정과 한계

- 실제 발행 메서드가 만든 snapshot은 수신자별 outbound까지 전달됐고 클라이언트의 STOMP 헤더에는 없었다. 일반 `Map`을 native 헤더로 취급하는 구현으로 바뀌면 이 회귀 시험이 실패해야 한다. 후보는 사용자 ID뿐 아니라 물리 세션 ID도 보존해 같은 사용자의 새 세션이 이전 push를 받지 않게 한다.
- 실제 HTTP 회수 200·DB 역할 삭제 뒤 **새 push**는 차단됐다. Redis 무효화 Lua 실행을 시험에서 실패시켜 낡은 `ADMIN` 캐시가 남은 경우도 차단됐다. Redis 전체 장애를 실제로 발생시키거나 GCP OpenProxy를 경유해 확인한 시험은 아니다.
- 결정적 latch 시험에서는 **회수 전에 이미 허용된 메시지 1건**이 200 뒤 수신됐다. 이를 새 판정 오류로 세지 않는다.
- primary 일괄 조회 실패 단위 시험에서는 발행 0건, 스케줄러 dirty 신호 복구, 이미 커밋된 재처리 성공 응답 유지가 확인됐다. 실제 GCP primary 프로세스 장애 주입은 별도 HA 시험이다.
- 성능 개선량, 두 백엔드의 SimpleBroker 이벤트 분배, 장애 전환 시 RPO는 측정하지 않았다. 동일한 새 GCP 환경에서 변경 전후 부하 재측정이 다음 작업이다.

## 정리·비식별

통합 테스트의 시험용 사용자·역할·Redis 키는 각 테스트가 정리했다. 최종 전체 시험 후 이번 실행만을 위해 만든 로컬 PostgreSQL·Redis 컨테이너 두 개를 정확한 이름으로 종료했고, `docker ps -a`에서 두 컨테이너가 남지 않은 것을 확인했다. 기존 GCP 부하 환경과는 별개이며 변경하지 않았다. 원문 XML·로그에 포함될 수 있는 로컬 호스트명, DB 접속 주소, 계정, 서명 문자열을 공개본에 복사하지 않았다. 저장소 결과 파일에 운영 비밀·GCP 프로젝트 ID·내부 IP를 기록하지 않았다.
