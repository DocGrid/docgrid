# STOMP 목적지 인가 정책 테스트 결과 (#358)

## 1. 검증 목적

- 정확한 dashboard·RAG 목적지만 현재 권한 조건으로 구독할 수 있는지 확인한다.
- SimpleBroker pattern 구독과 내부 queue 직접 접근이 interceptor에서 거부되는지 확인한다.
- client `SEND`와 raw `MESSAGE`가 거부되고 서버 push는 계속 전달되는지 확인한다.
- 기존 STOMP 연결 인증, HTTP 인증, dashboard push에 회귀가 없는지 확인한다.

## 2. 실행 환경

| 항목 | 값 |
|---|---|
| 실행 일시 | 2026-09-28 KST |
| branch | `fix/358` |
| Spring Boot | 3.5.16 |
| Spring Messaging | 6.2.19 |
| PostgreSQL | `docgrid-postgres17`, host port `55433` |
| Redis | `docgrid-redis`, host port `6379` |
| profile | `test` |

JWT secret은 실행 환경에만 test 전용 값으로 주입했고 저장소에는 기록하지 않았다.

## 3. 변경 전 실패 재현

새 단위 테스트를 기존 목적지 로직에 먼저 실행했다.

```bash
./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.auth.websocket.StompDestinationAuthorizationInterceptorTest'
```

결과:

```text
19 tests completed, 14 failed
BUILD FAILED
```

기존에 이미 보호되던 정확한 dashboard 경로는 통과했다. 다음 14건은 기대한 거부가 발생하지 않아
실패했다.

- Principal 없는 RAG 정확한 구독 1건
- pattern·내부 queue·알 수 없는 목적지·null 구독 9건
- dashboard 이외 목적지와 null 목적지 client SEND 4건

## 4. 수정 후 단위 테스트와 추가 우회 검토

정확한 두 목적지의 허용 목록과 client SEND 전면 거부를 적용한 뒤 같은 테스트를 다시 실행했다.

```text
19 tests completed, 0 failed
BUILD SUCCESSFUL
```

최종 코드 검토에서 raw STOMP `MESSAGE` command가 Spring에서 inbound message로 처리되는 것을 확인했다.
기존 `StompCommand.SEND` 조건에 우회가 있음을 단위 테스트로 먼저 재현했다.

```text
1 test completed, 1 failed
BUILD FAILED
```

`SEND`와 `MESSAGE`가 공통으로 변환되는 `SimpMessageType.MESSAGE`를 차단하도록 고친 뒤 최종 단위
테스트를 다시 실행했다.

```text
20 tests completed, 0 failed
BUILD SUCCESSFUL
```

## 5. 실제 WebSocket·SimpleBroker 통합 테스트

실행 명령:

```bash
DB_PORT=55433 ./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.auth.integration.StompDestinationAuthorizationIntegrationTest'
```

결과:

```text
5 tests completed, 0 failed
BUILD SUCCESSFUL in 8s
```

| 시나리오 | 실제 결과 |
|---|---|
| 두 사용자가 `/user/queue/rag-answer` 구독 후 한 사용자에게 `queryId=42` 전송 | 대상만 수신 |
| 일반 사용자가 `/topic/**` 구독 | 연결 오류로 거부 |
| 일반 사용자가 `/queue/*` 구독 | 연결 오류로 거부 |
| client가 `/user/{다른 사용자}/queue/rag-answer`로 SEND | 거부되고 대상에게 미전달 |
| client가 `/user/{다른 사용자}/queue/rag-answer`로 raw `MESSAGE` | 거부되고 대상에게 미전달 |

## 6. 주요 회귀 테스트

다음 여섯 테스트 클래스를 함께 실행했다.

```bash
DB_PORT=55433 ./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.auth.websocket.StompDestinationAuthorizationInterceptorTest' \
  --tests 'com.opensource.docgrid.domain.auth.integration.StompDestinationAuthorizationIntegrationTest' \
  --tests 'com.opensource.docgrid.domain.auth.jwt.StompAuthChannelInterceptorTest' \
  --tests 'com.opensource.docgrid.domain.auth.integration.StompConnectionAuthenticationIntegrationTest' \
  --tests 'com.opensource.docgrid.domain.dashboard.websocket.DashboardWebSocketIntegrationTest' \
  --tests 'com.opensource.docgrid.domain.auth.jwt.JwtAuthenticationFilterTest'
```

| 범위 | 건수 | 결과 |
|---|---:|---|
| 목적지 인가 단위 | 20 | PASS |
| 목적지 인가 실제 WebSocket | 5 | PASS |
| STOMP 연결 인증 단위 | 9 | PASS |
| STOMP 연결 인증 실제 WebSocket | 4 | PASS |
| dashboard WebSocket | 4 | PASS |
| HTTP JWT 인증 | 3 | PASS |
| 합계 | 45 | PASS |

```text
BUILD SUCCESSFUL in 12s
```

## 7. 전체 Backend 검증

```bash
JWT_SECRET={test-only-secret} DB_PORT=55433 \
  ./backend/gradlew -p backend test
```

Gradle XML test report 합산 결과:

```text
1216 tests completed, 0 failed
BUILD SUCCESSFUL in 51s
```

패키징 검증:

```bash
JWT_SECRET={test-only-secret} DB_PORT=55433 \
  ./backend/gradlew -p backend build
```

```text
BUILD SUCCESSFUL in 1s
```

## 8. 코드 재검토 결과

- `SEND`와 raw `MESSAGE`는 모두 `SimpMessageType.MESSAGE`가 되므로 command 문자열 우회 없이
  차단된다.
- `SimpMessagingTemplate`의 서버 push는 `clientInboundChannel`을 지나지 않아 client message 차단의
  영향을 받지 않는다.
- 현재 프런트는 허용한 두 목적지만 정확히 구독하고 client SEND를 사용하지 않는다.
- null 목적지, Principal 없음, 내부 queue, pattern과 알 수 없는 정확한 목적지도 모두 거부한다.
- 연결 인증 interceptor가 먼저 등록돼 SUBSCRIBE 시점에는 검증된 Principal이 존재한다.
- 문자열 동등 비교만 수행하므로 추가 DB·Redis 호출과 의미 있는 성능 비용이 없다.

남은 제한은 이미 열린 session의 역할 변경·로그아웃·token 만료 반영이다. 이 문제는 연결 수명 정책이
필요하므로 #358 범위에 포함하지 않았다.
