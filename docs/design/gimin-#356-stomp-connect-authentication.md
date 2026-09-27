# STOMP 연결 인증 규칙 보강 설계 (#356)

closes #356

## 1. 배경

DocGrid의 WebSocket HTTP handshake(`/ws`)는 모든 transport가 같은 방식으로 인증 정보를 전달할 수
있도록 열어 두고, 실제 사용자 인증은 inbound STOMP 연결 frame에서 수행한다. 인증에 성공하면
`StompAuthChannelInterceptor`가 `Authentication`을 WebSocket session의 Principal로 등록하고,
이후 구독 권한 검사는 그 Principal을 사용한다.

STOMP 1.2 client는 연결 명령으로 `CONNECT`뿐 아니라 `STOMP`도 보낼 수 있다. 두 명령은 Spring
Messaging 내부에서 모두 `SimpMessageType.CONNECT`로 표현되지만, 기존 코드는 원본 명령을
`StompCommand.CONNECT`와 비교했다. 따라서 `STOMP` 명령은 인증 분기를 지나지 않았다.

또한 기존 WebSocket 인증은 JWT의 서명과 만료만 확인했다. 로그아웃 시 access token의 `jti`를 Redis
blacklist에 기록하는 HTTP 인증 계약을 적용하지 않아, 로그아웃한 token도 만료 전까지 새 WebSocket
session을 만들 수 있었다.

## 2. 문제 상황

### 2.1 `STOMP` 명령이 인증 경계를 우회한다

기존 조건은 다음과 같았다.

```java
if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
    // JWT 검증과 Principal 등록
}
```

raw WebSocket client가 첫 frame을 `STOMP`로 보내면 다음 순서가 된다.

```text
WebSocket handshake 성공
→ STOMP 명령 수신
→ CONNECT와의 원본 명령 비교가 false
→ JWT 검증 없이 frame 통과
→ 익명 STOMP session 생성
```

연결 명령이라는 의미는 같지만 wire-level 명령 이름이 다르다는 이유로 인증 결과가 달라졌다.

### 2.2 로그아웃 token으로 새 session을 만들 수 있다

HTTP 요청은 `JwtAuthenticationFilter`에서 `jti` blacklist를 조회한다. 기존 WebSocket 연결은
`JwtProvider.getClaimsIfValid()`만 호출해 서명·형식·만료만 검증했다. 로그아웃으로 Redis에 등록된
token도 암호학적으로는 유효하므로 WebSocket 연결이 허용됐다.

```text
로그인 → access token 발급
→ 로그아웃 → jti를 Redis blacklist에 저장
→ 같은 token으로 WebSocket CONNECT
→ 서명·만료 검증 성공
→ 새 session 생성
```

## 3. 목표와 비목표

### 목표

- `CONNECT`와 `STOMP`를 같은 연결 인증 경계로 처리한다.
- JWT 서명·형식·만료와 `jti` blacklist를 모두 통과한 token만 Principal을 만든다.
- blacklist 상태를 확인할 수 없으면 신규 WebSocket 연결을 거부한다.
- raw WebSocket frame으로 실제 서버 동작을 검증한다.
- Redis 장애 시 인증 요청이 장시간 대기하지 않도록 읽기·접속 timeout을 함께 제한한다.

### 비목표

- 이미 열린 WebSocket session을 로그아웃 즉시 강제 종료하지 않는다.
- token 만료 시점에 기존 session을 자동 종료하지 않는다.
- `SUBSCRIBE` destination 권한 규칙과 wildcard 허용 범위는 변경하지 않는다.
- HTTP 인증의 Redis 장애 정책은 변경하지 않는다.

이미 열린 session은 연결 시점의 Principal을 재사용한다. 로그아웃·만료를 기존 session에 즉시
반영하려면 session registry와 강제 종료 정책이 추가로 필요하므로 별도 작업으로 다룬다.

## 4. 해결 설계

### 4.1 원본 명령이 아니라 연결 메시지 의미를 검사한다

```java
if (accessor != null && SimpMessageType.CONNECT.equals(accessor.getMessageType())) {
    // 두 STOMP 연결 명령에 동일한 인증 절차 적용
}
```

Spring은 `CONNECT`와 `STOMP`를 모두 `SimpMessageType.CONNECT`로 변환한다. 프로토콜 표면의 명령
문자열 대신 애플리케이션이 필요로 하는 의미를 기준으로 분기해 두 명령의 동작을 일치시킨다.

`SEND`, `SUBSCRIBE`, `DISCONNECT` 같은 후속 frame은 이 interceptor에서 token을 다시 파싱하지
않는다. 연결 때 session에 등록한 Principal을 사용하고, destination 권한은 기존
`StompDestinationAuthorizationInterceptor`가 담당한다.

### 4.2 인증 절차를 네 단계로 고정한다

```text
1. SimpMessageType.CONNECT인지 확인
2. Bearer JWT의 서명·형식·만료 검증
3. jti 존재 및 Redis blacklist 확인
4. 현재 DB 권한을 조회해 session Principal 등록
```

`jti`가 없으면 폐기 여부를 추적할 수 없으므로 거부한다. blacklist token 또는 Redis 오류가 확인된
경우 권한 DB를 조회하기 전에 중단해 불필요한 조회와 Principal 생성을 막는다.

외부에는 모든 인증 실패를 같은 `AccessDeniedException` 메시지로 반환한다. token 형식 오류,
blacklist 여부, Redis 장애를 구분해 노출하지 않아 인증 상태에 대한 추가 정보를 제공하지 않는다.

### 4.3 WebSocket 신규 연결은 Redis 장애 시 fail-closed한다

HTTP와 WebSocket은 장애의 영향 시간이 다르다.

| 경로 | Redis 조회 실패 정책 | 이유 |
|---|---|---|
| HTTP 요청 | 기존 fail-open 유지 | 일시 장애가 전체 API 요청을 중단시키는 영향을 피한다. |
| WebSocket 신규 연결 | fail-closed | 한 번 허용한 session이 Redis 복구 뒤에도 오래 유지될 수 있고, dashboard는 REST polling으로 대체할 수 있다. |

`TokenBlacklistService.isBlacklisted()`가 runtime exception을 던지면 오류를 기록하고 연결을 거부한다.
이 정책은 신규 연결에만 적용된다.

### 4.4 Redis 읽기 timeout과 접속 timeout을 모두 제한한다

기존 `spring.data.redis.timeout=1s`는 명령 응답 대기 시간을 제한한다. 연결 자체가 성립하지 않는
장애도 짧게 끝나도록 다음 설정을 추가한다.

```yaml
spring:
  data:
    redis:
      timeout: ${REDIS_TIMEOUT:1s}
      connect-timeout: ${REDIS_CONNECT_TIMEOUT:1s}
```

운영자는 두 값을 독립적으로 조정할 수 있고, 기본값은 모두 1초다.

## 5. 테스트 설계

### 단위 테스트

`StompAuthChannelInterceptorTest`에서 다음 분기와 의존성 호출 순서를 검증한다.

- 유효한 token은 `CONNECT`·`STOMP` 모두 Principal을 등록한다.
- Authorization header가 없거나 JWT가 무효면 blacklist·권한 조회를 호출하지 않는다.
- `jti`가 없으면 blacklist와 권한 조회 없이 거부한다.
- blacklist token과 Redis 조회 오류는 권한 조회 전에 거부한다.
- 연결이 아닌 frame은 인증 의존성을 호출하지 않고 통과한다.

### 실제 WebSocket 통합 테스트

`StompConnectionAuthenticationIntegrationTest`는 Spring Boot를 random port로 띄우고 SockJS의
native WebSocket endpoint(`/ws/websocket`)에 raw STOMP frame을 전송한다.

- 유효한 `STOMP` 명령은 `CONNECTED`를 받고 `SimpUserRegistry`에 `Authentication` Principal을
  등록한다.
- token 없는 `STOMP` 명령은 `ERROR`, 연결 종료 또는 transport error로 거부된다.
- blacklist token은 `CONNECT`·`STOMP` 모두 session을 등록하지 못한다.

`CONNECTED` 응답만 확인하면 익명 연결도 성공으로 오판할 수 있다. 정상 케이스는 registry의 사용자와
Principal 타입까지 확인한다.

## 6. 변경 범위

| 파일 | 변경 내용 |
|---|---|
| `StompAuthChannelInterceptor.java` | 연결 메시지 의미 기반 분기, `jti` blacklist, fail-closed 적용 |
| `StompAuthChannelInterceptorTest.java` | 두 연결 명령과 실패 분기의 단위 테스트 보강 |
| `StompConnectionAuthenticationIntegrationTest.java` | raw WebSocket 기반 실제 연결 테스트 추가 |
| `application.yml` | Redis connect timeout과 장애 정책 설명 추가 |
| `.env.example` | `REDIS_CONNECT_TIMEOUT` 설정 예시 추가 |

## 7. 남아 있는 범위

- 로그아웃·token 만료 후 기존 session을 종료하는 session lifecycle 정책
- dashboard destination의 pattern·wildcard subscription 권한 규칙(후속 #358에서 해결)
- 로그아웃 blacklist 기록 자체가 실패했을 때의 전달 보장
- Redis 재시작으로 blacklist가 유실되지 않도록 하는 persistence·운영 정책

이번 변경은 위 항목을 추측해 함께 확장하지 않고, 신규 STOMP 연결의 인증 계약 불일치만 수정한다.
