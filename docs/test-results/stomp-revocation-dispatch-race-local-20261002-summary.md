# STOMP 권한 회수·진행 중 전송 경합 — 로컬 시험 종합

목적: 권한 확인이 끝난 MESSAGE의 실제 전송을 의도적으로 멈추고, 그 사이 HTTP 회수 200·DB 커밋이 완료되면 클라이언트가 해당 진행 중 MESSAGE를 나중에 받을 수 있는지 확인한다. 이 문서는 로컬 PostgreSQL·Redis·WebSocket 통합 시험의 종합이며 **GCP 3노드/OpenProxy나 부하 측정 결과가 아니다**.

| 실행 ID | 목적·조건 | 결과 | 판정·근거 |
| --- | --- | --- | --- |
| [run01](stomp-revocation-dispatch-race-local-20261002-run01.md) | 새 격리 DB에서 첫 실행 | 테스트 2개 컨텍스트 실패, 경합 본문 0건 | pgvector 확장 누락으로 Flyway V32 중단. 기능 판정 제외 |
| [run02](stomp-revocation-dispatch-race-local-20261002-run02.md) | 격리 DB에 pgvector를 활성화한 재실행 | **2/2 통과**, 1.887초 | 실제 HTTP 회수 후 진행 중 메시지 1건 수신 재현 |
| [run03](stomp-revocation-dispatch-race-local-20261002-run03.md) | 같은 조건 강제 재실행 | **2/2 통과**, 1.914초 | 동일 경합 재현 |
| [run04](stomp-revocation-dispatch-race-local-20261002-run04.md) | 세 번째 성공 재현 | **2/2 통과**, 1.881초 | 동일 경합 재현, 시험 사용자·Redis 역할 키 잔여 0건 |

관측한 순서는 `primary 역할 확인 완료 → outbound 전송 보류 → HTTP 회수 200·DB ADMIN 삭제 → 전송 해제 → 보류 중 메시지 수신`이다. 회수 이후 **새로 시작한 push**를 차단하는 기존 시험도 성공 반복 3회에 함께 통과했다. 따라서 이번 결과는 “회수 뒤 새 권한 확인이 잘못 ADMIN을 반환한다”가 아니라 **회수 전에 허용한 진행 중 메시지의 늦은 전달**을 가리킨다.

테스트는 `@MockitoSpyBean`으로 실제 outbound 인터셉터의 `preSend`를 먼저 실행한 뒤 반환을 latch로 보류한다. 역할 변경은 mock이 아니라 실제 HTTP API·PostgreSQL 트랜잭션·Redis 무효화 경로를 사용한다. 테스트 전용 Java 코드 외 프로덕션 코드는 변경하지 않았다.

정리: 테스트 전용 사용자 **0건**, 격리 Redis 역할 키 **0건**을 확인했다. 이번 시험에 만든 PostgreSQL·Redis 컨테이너 두 개를 정확한 이름으로 중지했고 자동 삭제 후 `docker ps -a` 잔여 **0개**를 확인했다. 기존 로컬 컨테이너와 GCP 3노드는 삭제·변경하지 않았다.

제한: Gradle의 기본 JUnit XML 경로는 반복 실행 시 같은 파일을 다시 쓰므로, 첫 세 성공 실행의 XML 원문은 별도 보존하지 못했다. 각 실행 직후 확인한 건수·실행 시간은 위 실행별 한국어 로그에 분리해 기록했다. 실제 GCP 3노드, 두 백엔드, 부하 중 권한 회수, 전송 시작 시각과 클라이언트 수신 시각의 분산 계측은 아직 수행하지 않았다.
