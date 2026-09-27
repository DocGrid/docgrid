# STOMP 연결 인증 보강 테스트 결과 (#356)

## 1. 검증 목적

다음 계약이 코드 단위와 실제 WebSocket 서버 양쪽에서 성립하는지 확인했다.

- `CONNECT`와 `STOMP`가 동일한 JWT 인증 절차를 거친다.
- 로그아웃으로 blacklist에 등록된 token은 새 session을 만들지 못한다.
- Redis blacklist를 확인할 수 없는 신규 연결은 거부된다.
- 기존 HTTP 인증과 dashboard WebSocket push 흐름에 회귀가 없다.

## 2. 실행 환경

| 항목 | 값 |
|---|---|
| 실행 일시 | 2026-09-28 KST |
| Java | 프로젝트 Gradle test runtime |
| PostgreSQL | `docgrid-postgres17`, host port `55433` |
| Redis | `docgrid-redis`, host port `6379` |
| Spring profile | `test` |
| 기준 branch | `fix/356` |

JWT secret은 테스트 실행 환경 변수로만 주입했으며 저장소에는 기록하지 않았다.

## 3. 변경 전 확인

기준 코드에서 raw WebSocket probe와 인증 interceptor 호출 경로를 각각 확인했다.

| 입력 | 관찰 결과 | 판정 |
|---|---|---|
| Authorization header 없는 `STOMP` 연결 명령 | `CONNECTED` 응답 수신 | 연결 인증 분기 우회 |
| 유효한 JWT를 포함한 `STOMP` 연결 명령 | token의 권한과 무관하게 익명 session으로 처리 | `StompCommand.CONNECT`만 비교한 결과 |
| 연결 interceptor의 호출 경로 | JWT 검증 뒤 바로 권한 조회 | blacklist 호출 자체가 없음 |

첫 두 결과는 실제 frame probe에서 관찰했고, blacklist 누락은 기존 호출 경로와 단위 테스트로 확인했다.
원인은 연결 여부를 `StompCommand.CONNECT`로만 판정하고
`TokenBlacklistService.isBlacklisted(jti)`를 호출하지 않은 것이었다.

## 4. 단위 및 주요 회귀 테스트

실행 명령:

```bash
DB_PORT=55433 ./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.auth.jwt.StompAuthChannelInterceptorTest' \
  --tests 'com.opensource.docgrid.domain.auth.jwt.JwtAuthenticationFilterTest' \
  --tests 'com.opensource.docgrid.domain.auth.integration.StompConnectionAuthenticationIntegrationTest' \
  --tests 'com.opensource.docgrid.domain.dashboard.websocket.DashboardWebSocketIntegrationTest'
```

결과:

```text
BUILD SUCCESSFUL in 9s
```

| 테스트 클래스 | 건수 | 결과 | 검증 내용 |
|---|---:|---|---|
| `StompAuthChannelInterceptorTest` | 9 | PASS | 두 연결 명령, 무토큰, 무효 JWT, `jti` 누락, blacklist, Redis 오류, 비연결 frame |
| `JwtAuthenticationFilterTest` | 3 | PASS | 기존 HTTP 인증과 Redis fail-open 정책 유지 |
| `StompConnectionAuthenticationIntegrationTest` | 4 | PASS | raw WebSocket의 정상·거부·blacklist 연결 |
| `DashboardWebSocketIntegrationTest` | 4 | PASS | 기존 dashboard push·권한 흐름 회귀 없음 |

합계 20건이 통과했다.

## 5. raw WebSocket 통합 검증

테스트 client는 SockJS JavaScript client를 거치지 않고 `/ws/websocket`에 직접 연결해 다음 frame을
전송한다.

```text
STOMP
accept-version:1.2
Authorization:Bearer {token}
heart-beat:0,0

\0
```

| 시나리오 | 기대 결과 | 실제 결과 |
|---|---|---|
| 유효한 JWT + `STOMP` | 연결 성공, 인증 Principal 등록 | PASS |
| token 없음 + `STOMP` | 연결 거부 | PASS |
| blacklist JWT + `STOMP` | 연결 거부, 사용자 미등록 | PASS |
| blacklist JWT + `CONNECT` | 연결 거부, 사용자 미등록 | PASS |

정상 시나리오는 `CONNECTED` frame뿐 아니라 `SimpUserRegistry.getUser(email)`이 반환하는 Principal이
`Authentication`인지 확인했다. 거부 시나리오는 `ERROR` frame, 연결 종료, transport error 가운데 하나가
발생하고 registry에 사용자가 등록되지 않음을 확인했다.

## 6. 전체 Backend 테스트

최초 전체 실행은 `JWT_SECRET` 환경 변수가 없는 독립 worktree에서 Spring Context 생성이 실패했다.
보고서의 공통 원인은 다음과 같았다.

```text
Could not resolve placeholder 'JWT_SECRET' in value "${JWT_SECRET}"
```

테스트용 secret과 실제 PostgreSQL port를 주입해 동일 전체 suite를 다시 실행했다.

```bash
JWT_SECRET={test-only-secret} DB_PORT=55433 \
  ./backend/gradlew -p backend test
```

결과:

```text
1172 tests completed, 0 failed
BUILD SUCCESSFUL in 53s
```

첫 실행의 실패는 애플리케이션 코드 assertion 실패가 아니라 필수 환경 변수 누락이었으며, 환경을
보완한 재실행에서 전체 1,172건이 통과했다.

## 7. 결론과 한계

신규 STOMP 연결에서 두 연결 명령이 같은 인증 경계를 통과하고, 로그아웃 token과 blacklist 상태를
확인할 수 없는 token이 거부됨을 단위·실제 WebSocket·전체 회귀 테스트로 확인했다.

이번 결과는 연결 시점 인증을 대상으로 한다. 연결 후 로그아웃·만료된 기존 session의 강제 종료는
별도 범위이며, pattern·wildcard subscription 권한 검증은 후속 #358에서 보강했다.
