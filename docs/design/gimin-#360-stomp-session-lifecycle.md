# 열린 STOMP 세션 인증 상태 재검증 설계 (#360)

closes #360

## 1. 배경

DocGrid는 STOMP CONNECT에서 JWT의 서명·만료와 Redis blacklist를 확인하고, DB의 현재 역할로
`Authentication` Principal을 만든다. 이후 SUBSCRIBE 인가는 이 Principal을 재사용한다.

이 방식은 연결 순간의 인증은 보호하지만, WebSocket 연결이 오래 유지되는 동안 바뀐 상태를 Principal에
반영하지 못한다. HTTP 요청은 매번 인증 필터를 다시 지나지만, 열린 STOMP 세션은 새 요청 없이 같은
Principal을 계속 사용하기 때문이다.

## 2. 문제 상황

기존 동작에서는 다음 세 경우 모두 연결 당시 권한이 남았다.

```text
유효한 JWT로 CONNECT
→ 서버가 Principal을 session에 저장
→ 로그아웃 / JWT 만료 / 관리자 역할 회수
→ 기존 session은 계속 구독 상태 유지
→ 연결 당시 권한으로 push 수신
```

- 로그아웃은 jti를 Redis blacklist에 넣지만 이미 열린 세션은 다시 조회하지 않았다.
- JWT 만료는 CONNECT 때만 검사해 만료 시각이 지난 뒤에도 물리 연결이 유지됐다.
- 역할 변경은 DB와 Redis 역할 캐시를 갱신하지만 세션 Principal의 authority는 바뀌지 않았다.

프런트가 새로 연결할 때는 최신 상태가 적용되지만, 사용자가 페이지를 닫거나 네트워크가 끊길 때까지 기존
연결이 살아 있을 수 있어 인증 경계가 연결 수명 전체에 적용되지 않았다.

## 3. 목표와 비목표

### 목표

- 열린 STOMP 세션에 JWT 만료, blacklist 등록, 현재 역할 변경을 반영한다.
- 세션 수만큼 Redis·DB를 호출하지 않고 일괄 조회한다.
- Redis 또는 DB에서 상태를 확인할 수 없으면 열린 연결도 fail-closed로 종료한다.
- 여러 Backend 인스턴스에서 별도 분산 lock 없이 각 인스턴스가 소유한 연결을 안전하게 검사한다.
- 활성 세션과 종료 이유를 Metric으로 관찰할 수 있게 한다.

### 비목표

- blacklist 기록 자체의 전달 보장과 Redis persistence 정책은 변경하지 않는다.
- STOMP frame rate limit, message 크기 제한, CONNECT를 보내지 않은 물리 연결의 timeout은 다루지 않는다.
- Redis Pub/Sub을 추가해 상태 변경 순간에 즉시 연결을 끊지 않는다.
- HTTP 인증의 Redis 장애 정책은 변경하지 않는다.

## 4. 핵심 설계

### 4.1 물리 WebSocket 연결을 먼저 추적한다

강제로 연결을 닫으려면 STOMP의 논리 session 정보만으로는 부족하고 실제 `WebSocketSession`이 필요하다.
`StompSessionTrackingDecoratorFactory`를 transport에 등록해 물리 연결이 성립하는 순간 sessionId와
`WebSocketSession`을 `StompSessionRegistry`에 넣는다.

```text
1. WebSocket transport 연결 성립
   → registry에 물리 session 등록
2. STOMP CONNECT 인증 성공
   → 같은 sessionId에 인증 snapshot 결합
3. 네트워크 종료 또는 서버 강제 종료
   → registry entry 제거
```

CONNECT 인증은 물리 session이 registry에 있을 때만 성공시킨다. 따라서 추적에서 빠진 인증 세션이
생기지 않는다. transport 설정 실패와 종료는 `finally` 경계에서 entry를 정리한다.

### 4.2 JWT 원문 대신 최소 인증 snapshot을 저장한다

연결마다 다음 값만 불변 snapshot으로 보관한다.

| 값 | 용도 |
|---|---|
| `userId` | 현재 역할 일괄 조회 |
| `jti` | Redis blacklist 일괄 조회 |
| `expiresAt` | 외부 조회 없는 만료 판정 |
| `roles` | 연결 당시 역할과 현재 역할 비교 |

JWT 원문과 이메일은 registry에 보관하지 않는다. 역할은 순서와 중복에 영향을 받지 않도록 `Set`으로
정규화한다.

### 4.3 고정 지연 스케줄러가 세 단계로 재검증한다

기본 5초 간격의 `StompSessionRevalidationScheduler`가 인증 완료 세션의 snapshot을 읽는다.

```text
1. expiresAt <= now
   → Redis·DB 조회 없이 1008 Policy Violation으로 종료

2. 남은 jti를 Redis MGET으로 조회
   → blacklist에 있는 세션 종료

3. 남은 userId의 현재 역할을 DB IN query로 조회
   → snapshot과 다르면 세션 종료
```

blacklist로 이미 폐기된 세션은 역할 조회 대상에서 제외한다. 같은 jti와 userId는 중복 제거하고 기본
500개씩 나눠 조회한다. 세션이 `N`, 고유 사용자가 `U`, 고유 jti가 `J`라면 한 검사 주기의 외부 호출은
최대 `ceil(J / 500)`번의 Redis MGET과 `ceil(U / 500)`번의 DB IN query다.

역할은 `RoleAuthorityService`의 짧은 Redis 캐시를 거치지 않고 DB에서 직접 읽는다. 재검증의 목적이
연결 당시 값과 source of truth를 비교하는 것이므로 오래된 cache snapshot을 다시 비교하지 않는다.

### 4.4 검증 불가능 상태는 fail-closed로 처리한다

Redis MGET 결과가 요청 key 수와 다르거나 Redis·DB 조회가 실패하면 검사 대상 연결을 모두 종료한다.
WebSocket push는 프런트의 기존 REST polling fallback으로 기능을 이어갈 수 있고, 검증하지 못한 연결을
장시간 유지하는 것보다 권한 경계를 닫는 편이 안전하다.

한 세션의 `close()`가 실패하면 registry에서 제거하지 않는다. 다음 검사에서 다시 시도할 수 있게 하기
위해서다. 이미 물리적으로 닫힌 세션은 snapshot을 만들 때 정리한다.

### 4.5 인스턴스별 registry로 수평 확장을 유지한다

물리 WebSocket 연결은 연결을 받은 Backend 인스턴스만 닫을 수 있다. 각 인스턴스는 로컬 registry를
검사하되 모든 인스턴스가 공유하는 Redis blacklist와 DB 역할을 읽는다. 그래서 분산 session registry,
leader election, scheduler lock이 필요하지 않다.

Redis Pub/Sub은 이벤트 유실과 재구독 시점의 상태 복구를 별도로 해결해야 한다. 주기 검사는 상태 자체를
다시 읽으므로 일시적인 이벤트 유실 개념이 없고, 권한 반영 지연의 상한은 검사 간격과 조회 시간이다.

## 5. 설정과 관측성

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `STOMP_SESSION_REVALIDATION_INTERVAL` | `5s` | 한 검사 종료 뒤 다음 검사까지의 지연 |
| `STOMP_SESSION_REVALIDATION_BATCH_SIZE` | `500` | Redis MGET·DB IN query 한 번의 고유 key 상한 |

| Metric | tag | 의미 |
|---|---|---|
| `docgrid.stomp.sessions.active` | 없음 | 현재 인스턴스의 열린 인증 세션 수 |
| `docgrid.stomp.sessions.closed` | `reason` | 재검증으로 종료한 누적 세션 수 |

`reason`은 `expired`, `blacklisted`, `roles_changed`, `validation_failed` 네 값으로 고정해 Metric label의
cardinality가 사용자 수나 token 수에 따라 늘지 않게 한다.

## 6. 경합과 실패 경계

- 검사 snapshot을 만든 뒤 client가 먼저 연결을 닫으면 `close()`는 열린 상태를 다시 확인하고 아무 작업도
  하지 않는다.
- 검사와 역할 변경 transaction이 겹치면 DB에 커밋된 상태만 보며, 다음 주기에 최종 상태를 반영한다.
- 같은 사용자의 여러 세션은 역할을 한 번 조회하고 각 snapshot을 독립적으로 비교한다.
- 서버 종료와 강제 종료가 겹쳐도 `ConcurrentMap.remove(key, value)`로 같은 entry만 제거한다.
- validation failure 중 일부 세션 종료가 실패해도 나머지 세션 처리를 계속하고 실패한 entry는 재시도한다.

## 7. 변경 범위

| 파일 | 변경 내용 |
|---|---|
| `StompSessionAuthorization.java` | 연결 당시 최소 인증 snapshot |
| `StompSessionRegistry.java` | 물리 연결·snapshot 결합, 조회와 강제 종료 |
| `StompSessionTrackingDecoratorFactory.java` | transport 연결 등록·해제 |
| `StompSessionRevalidationScheduler.java` | 만료·blacklist·역할 일괄 재검증과 Metric |
| `StompSessionSchedulingConfig.java` | 인증 수명 검사의 독립적인 scheduling 활성화 |
| `StompAuthChannelInterceptor.java` | CONNECT 성공 시 snapshot 등록 |
| `TokenBlacklistService.java` | Redis MGET 기반 blacklist 일괄 조회 |
| `WebSocketConfig.java` | transport decorator 등록 |
| `application.yml`, `.env.example` | 검사 간격·batch 설정 |

## 8. 검증 전략

단위 테스트는 registry 경합·종료 실패, MGET 결과, batch 분할, 만료 우선 처리, fail-closed와 종료 사유
Metric을 검증한다. 실제 WebSocket 통합 테스트는 연결 뒤 blacklist 등록, JWT 만료, 역할 변경이 세션을
종료하는지 확인하고, 상태가 바뀌지 않은 연결은 여러 검사 주기 뒤에도 push를 받는지 확인한다.

## 9. 남아 있는 범위

- 기본 5초보다 더 짧은 즉시 폐기가 필요하면 상태 변경 이벤트를 보조 신호로 추가할 수 있다. 최종
  정합성 검사는 현재 주기 작업을 유지해야 한다.
- 로그아웃 과정에서 blacklist 기록 자체가 실패하거나 Redis 재시작으로 key가 유실되면 이 검사도
  폐기 사실을 알 수 없다. 이는 blacklist 저장 신뢰성의 별도 문제다.
- 인증 전 물리 연결을 장시간 유지하는 client 제어는 handshake·CONNECT timeout 또는 연결 제한으로
  별도 설계해야 한다.
