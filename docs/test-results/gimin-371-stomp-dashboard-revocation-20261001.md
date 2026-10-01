# 관리자 역할 회수 후 WebSocket 대시보드 접근 차단 — 로컬 검증

관련 이슈: [#371](https://github.com/DocGrid/docgrid/issues/371)  
선행 재현: [#366 결과](gimin-%23365-stomp-dashboard-role-revocation-boundary.md)  
검증일: 2026-10-01

## 문제와 판정 경계

기존에는 STOMP CONNECT에서 읽은 `ROLE_ADMIN`을 세션 Principal에 저장했다. 이후 새
`/topic/dashboard` SUBSCRIBE는 그 snapshot만 확인했고, 이미 브로커에 등록된 구독에는
대시보드 메시지가 추가 권한 검사 없이 전달됐다. 따라서 관리자 역할 회수 HTTP 요청이 성공한 뒤에도
주기 세션 재검증이 실행되기 전에는 새 구독과 기존 구독 수신이 가능했다.

이번 수정의 판정 범위는 **역할 회수 HTTP 성공 응답 뒤 시작한 새 SUBSCRIBE와 새로 발행한
대시보드 MESSAGE**다. 회수와 동시에 이미 outbound 검사를 통과한 in-flight 메시지가 언제
클라이언트에 도착하는지는 보장하지 않는다.

```text
기존: CONNECT(ADMIN snapshot) ── 회수 HTTP 200 ── SUBSCRIBE 허용 / 기존 구독 push 수신
                                              └─ 주기 검사(기본 5초) 뒤 세션 종료

변경: CONNECT(ADMIN snapshot) ── 회수 HTTP 200 ── 새 SUBSCRIBE → primary 역할 조회 → 거부
                                              └─ 기존 구독의 새 MESSAGE → primary 역할 조회
                                                                         → 전송 취소 + 세션 종료
```

## 변경한 경계

| 경계 | 코드 | 결정 |
| --- | --- | --- |
| 새 관리자 구독 | `StompDestinationAuthorizationInterceptor` | CONNECT snapshot이 ADMIN이어도 `RoleAuthorityService.getRolesForAdmin()`으로 primary의 현재 역할을 다시 확인한다. 조회 실패도 거부한다. |
| 기존 구독의 새 push | `StompDashboardOutboundAuthorizationInterceptor` | `clientOutboundChannel`의 대시보드 MESSAGE마다 수신 세션을 찾고 primary 역할을 확인한다. 회수·조회 실패 시 메시지를 버리고 물리 세션을 닫는다. |
| 수신 세션 식별 | `StompSessionRegistry.authorizationFor()` | outbound MESSAGE의 `sessionId`로 해당 백엔드 인스턴스의 열린 WebSocket과 CONNECT 인증 snapshot을 찾는다. |
| 전송 파이프라인 | `WebSocketConfig.configureClientOutboundChannel()` | 새 outbound 인가기를 브로커와 실제 WebSocket 전송 사이에 등록한다. |

`PrimaryRoleQueryService`는 동일한 read-write 트랜잭션에서 `pg_is_in_recovery()`가
`false`인지 확인한 뒤 역할을 조회한다. Redis 역할 캐시나 standby 조회로 폴백하지 않는다.
일반 RAG 개인 알림은 이번 관리자 대시보드 검사에 포함하지 않는다. 주기 세션 재검증은
토큰 만료·로그아웃·역할 변경 감지를 위해 그대로 유지한다.

## 실행 위치·명령·관찰 결과

모든 명령은 분리된 `fix/371` worktree의 저장소 루트에서 실행했다. PostgreSQL과 Redis는
로컬 시험 서비스다. `<test-only-secret>`은 운영 시크릿이 아닌 시험용 문자열이다.

| 실행 위치 | 명령 또는 동작 | 관찰 결과 | 해석 |
| --- | --- | --- | --- |
| 로컬 worktree | `./backend/gradlew -p backend compileJava --offline` | `BUILD SUCCESSFUL` | 신규 outbound 인가기를 포함한 프로덕션 코드 컴파일 성공. |
| 로컬 worktree | `./backend/gradlew -p backend test --offline --tests '*StompDestinationAuthorizationInterceptorTest' --tests '*StompDashboardOutboundAuthorizationInterceptorTest'` | `BUILD SUCCESSFUL` | 새 구독·outbound 인가의 역할 회수와 primary 장애 분기를 단위 수준에서 확인. |
| 로컬 worktree | `./backend/gradlew -p backend test --offline --tests '*StompDashboardRoleRevocationIntegrationTest' --tests '*StompDashboardRealRoleRevocationIntegrationTest' --tests '*StompSessionRegistryTest'` | `BUILD SUCCESSFUL` | mock 역할 변경과 실제 HTTP·PostgreSQL·Redis 회수 흐름을 모두 통과. |
| 로컬 worktree | `JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test --offline` (최종 통합 시험 변경 전) | 209 suite, 1,257 test, 실패·오류·건너뜀 0; `BUILD SUCCESSFUL` | 당시 전체 회귀 통과. 마지막 시험 강화 이후의 전체 통과 증거로 사용하지 않는다. |
| 로컬 worktree | 위 전체 명령 (최종 통합 시험 변경 후) | 1,255건 중 1건 실패; Sync 통합 Context 생성 시 PostgreSQL `FATAL: sorry, too many clients already` | WebSocket assertion 실패가 아닌 로컬 DB 연결 한도 문제. 전체 회귀는 최종 상태에서 통과하지 못했다. |
| 로컬 worktree | Spring Test Context 캐시 상한 8을 임시 적용한 전체 명령 | 1,257건 중 68건 실패; 동일한 PostgreSQL 연결 한도 문제 | 캐시 축소만으로 해결되지 않았다. 저장소의 Gradle 설정은 변경하지 않았다. |
| 로컬 worktree | `JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test --offline --tests '*StompDashboardRealRoleRevocationIntegrationTest' --tests '*StompDashboardRoleRevocationIntegrationTest' --tests '*StompDestinationAuthorizationInterceptorTest' --tests '*StompDashboardOutboundAuthorizationInterceptorTest' --tests '*StompSessionRegistryTest'` | 5 suite, 36 test, 실패·오류·건너뜀 0; `BUILD SUCCESSFUL` | 최종 코드에서 직접 관련된 모든 시험 통과. |
| 로컬 worktree | `git diff --check` | 출력 없음 | whitespace 오류 없음. |

실제 DB·Redis 통합 시험에서는 두 ADMIN 사용자를 연결했다. 한 사용자의 역할만 HTTP API로
회수한 뒤, 회수된 사용자의 기존 구독에는 새 대시보드 메시지가 **0건** 도달했고 다른 정상
ADMIN 구독자는 같은 메시지를 수신했다. 기존 연결에서 새 SUBSCRIBE도 거부됐다. Redis 캐시
무효화를 확인한 후 의도적으로 오래된 `ADMIN` 값을 다시 넣어도 이 두 관리자 경계는 primary
역할을 사용해 거부됐다. 이 캐시 주입은 우회 방지 시험 조건이지 실제 운영에서 관찰한
캐시 부활 사건은 아니다. 자동 세션 재검증은 1시간 뒤로 늦추고 수동 호출도 하지 않았다.

## 비용과 미검증 범위

- 대시보드 MESSAGE는 **구독자별 전송마다 primary 역할 조회**가 추가된다. 즉, 전송량과
  구독자 수가 늘면 DB·OpenProxy 부하도 함께 증가한다. 처리량·p95·p99는 이번 로컬 기능
  시험에서 측정하지 않았으며 부하 시험 전에 운영 설정으로 간주하지 않는다.
- 이 시험은 로컬 단일 백엔드·PostgreSQL·Redis다. GCP 3노드 OpenProxy 경유, 복제 지연,
  다중 백엔드 인스턴스 사이의 결과를 실행해 확인하지 않았다. 각 인스턴스가 자기 세션에 대해
  primary를 확인하도록 설계했지만, 다중 인스턴스 보장은 별도 시험이 필요하다.
- 회수 HTTP 응답 전에 outbound 인가를 통과한 in-flight 메시지는 응답 뒤 도착할 수 있다.
  이번 결과의 0건은 **응답 뒤 새로 발행한 메시지**에 관한 것이다.
- 비동기 복제 failover가 이미 성공 응답한 역할 회수 커밋을 잃는다면 새 primary에서
  역할이 되돌아올 수 있다. 이 문제는 WebSocket 인가 코드만으로 해결할 수 없으며
  별도의 OpenSQL RPO 시험 대상이다.
- Redis 장애 시 CONNECT의 기존 fail-closed 계약과, 관리자 SUBSCRIBE·outbound의
  primary 조회 실패 시 fail-closed 계약은 단위 시험으로 확인했다. 실제 DB·프록시 장애를
  주입한 시험은 하지 않았다.

## 다음 검증

GCP 내부 부하 발생기에서 대시보드 구독자 수와 push 주기를 바꿔 primary 조회 비용·Hikari
대기·OpenProxy 연결 수·전달 지연을 측정한다. 이후 두 백엔드 인스턴스에 각각 WebSocket
세션을 열고 한쪽에서 역할을 회수해 양쪽의 새 구독과 새 push를 함께 확인한다.
