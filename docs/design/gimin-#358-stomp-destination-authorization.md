# STOMP 목적지 인가 정책 보강 설계 (#358)

closes #358

## 1. 배경

DocGrid는 Spring SimpleBroker로 두 종류의 WebSocket push를 제공한다.

| client 구독 목적지 | 용도 | 필요한 권한 |
|---|---|---|
| `/topic/dashboard` | 운영 dashboard 집계 push | `ROLE_ADMIN` |
| `/user/queue/rag-answer` | 요청 사용자별 RAG 답변 완료 알림 | 인증 사용자 |

연결 인증은 `StompAuthChannelInterceptor`가 CONNECT 시점에 JWT·blacklist를 확인하고 Principal을
session에 등록한다. 기존 목적지 interceptor는 `/topic/dashboard`와 정확히 같은 목적지만 ADMIN
권한을 확인하고, 나머지 목적지는 모두 통과시켰다.

Spring 6.2.19의 `DefaultSubscriptionRegistry`는 구독 목적지를 `AntPathMatcher` pattern으로도
등록한다. 따라서 interceptor의 문자열 동등 비교와 broker의 목적지 해석 범위가 달랐다.

## 2. 문제 상황

### 2.1 dashboard pattern 구독

일반 사용자가 다음 목적지를 구독하면 기존 interceptor의 정확한 문자열 검사를 통과한다.

```text
/topic/*
/topic/**
/topic*/**
/topic/dash{x}
```

이후 서버가 `/topic/dashboard`로 보낸 메시지를 SimpleBroker가 pattern 구독자에게 전달한다. 선행
작업 #356으로 익명 연결은 차단됐지만, 유효한 일반 사용자 token으로 관리자 집계 정보를 받는 수직
권한 상승은 남아 있었다.

### 2.2 RAG 사용자 queue pattern 구독

`convertAndSendToUser`는 `/user/queue/rag-answer`를 session별 실제 `/queue` 목적지로 변환한다.
기존 interceptor는 `/queue/*` 구독을 검사하지 않아 다른 사용자에게 전달되는 `queryId`와 완료 시점을
pattern 구독으로 받을 수 있었다. 답변 본문은 소유권을 검사하는 REST에서 다시 읽으므로 직접 노출되지는
않지만, 사용자 queue 격리 계약은 깨진다.

### 2.3 client SEND

기존 코드는 정확한 `/topic/dashboard`로 보내는 SEND만 거부했다. dashboard 이외 topic과 사용자
queue로 보내는 client SEND는 inbound channel을 통과할 수 있었다. 현재 프런트에는 client SEND가
없고 서버 push는 모두 `SimpMessagingTemplate`에서 시작하므로 이를 허용할 기능상 이유가 없다.

## 3. 목표와 비목표

### 목표

- client가 구독할 수 있는 목적지를 현재 사용하는 두 개의 정확한 주소로 제한한다.
- dashboard는 ADMIN, RAG 사용자 queue는 인증 사용자만 구독할 수 있게 한다.
- pattern, broker 내부 queue, 알 수 없는 목적지와 목적지 없는 구독을 거부한다.
- 모든 client SEND를 거부한다.
- 서버의 `SimpMessagingTemplate` push와 현재 프런트 동작을 유지한다.

### 비목표

- 새로운 WebSocket destination과 client message 기능을 추가하지 않는다.
- 로그아웃·token 만료 후 이미 열린 session을 강제로 종료하지 않는다.
- STOMP frame rate limit과 message 크기 제한은 다루지 않는다.

## 4. 정책

| STOMP command | 목적지 | Principal | 결과 |
|---|---|---|---|
| `SUBSCRIBE` | `/topic/dashboard` | 인증된 `ROLE_ADMIN` | 허용 |
| `SUBSCRIBE` | `/user/queue/rag-answer` | 인증 사용자 | 허용 |
| `SUBSCRIBE` | pattern·내부 queue·그 외·없음 | 무관 | 거부 |
| `SEND` | 모든 목적지 | 무관 | 거부 |
| 그 외 | 해당 없음 | 기존 연결 정책 | 통과 |

pattern 문자를 별도로 판별하지 않는다. 두 허용 목적지와 정확히 같은 경우만 허용하면 Spring matcher의
종류나 설정이 바뀌어도 보안 경계가 넓어지지 않는다. 새 destination을 추가할 때는 인가 조건과 테스트를
함께 추가해야 한다.

## 5. 구현

### 5.1 책임 이동

dashboard domain에 있던 `DashboardSubscriptionAuthorizationInterceptor`를 인증 domain의
`StompDestinationAuthorizationInterceptor`로 이동한다. 정책이 dashboard, RAG, client SEND를 함께
다루므로 이름과 package를 실제 책임에 맞춘다.

### 5.2 실행 순서

```text
1. StompAuthChannelInterceptor
   → CONNECT·STOMP JWT, jti blacklist, 현재 역할 검증
   → Authentication Principal 등록

2. StompDestinationAuthorizationInterceptor
   → SEND 전부 거부
   → SUBSCRIBE 목적지·Principal 인가
   → 그 외 command 통과
```

`WebSocketConfig`는 이 순서로 두 interceptor를 등록한다. 목적지 interceptor가 먼저 실행되면 정상
SUBSCRIBE 시점에 Principal이 없어 모두 거부되므로 순서를 바꾸면 안 된다.

### 5.3 server push와 client SEND 분리

client frame만 `clientInboundChannel`을 통과한다. `DashboardWebSocketController`와
`RagWebSocketController`가 사용하는 `SimpMessagingTemplate`은 이 interceptor를 통과하지 않으므로
client SEND 전체 거부가 서버 push를 막지 않는다.

### 5.4 실패 응답

인가 실패는 `AccessDeniedException`으로 중단한다. 외부 응답에서는 알 수 없는 목적지, pattern,
권한 부족을 자세히 구분하지 않는다. 공격자가 허용 목록 구조를 오류 메시지로 탐색하지 못하게 하고,
서버 로그에도 사용자가 입력한 destination 원문을 추가로 남기지 않는다.

## 6. 변경 범위

| 파일 | 변경 내용 |
|---|---|
| `StompDestinationAuthorizationInterceptor.java` | 정확한 목적지 허용 목록, 역할·인증 검사, SEND 거부 |
| `WebSocketConfig.java` | 이동한 interceptor 등록과 `/queue` 경계 설명 갱신 |
| `SecurityConfig.java` | STOMP 3단계 보안 설명의 클래스 이름 갱신 |
| `RagWebSocketController.java` | user destination과 inbound 허용 목록의 공동 격리 책임 설명 |
| `StompDestinationAuthorizationInterceptorTest.java` | 허용·거부 정책 단위 테스트 |
| `StompDestinationAuthorizationIntegrationTest.java` | 실제 WebSocket·SimpleBroker 전달 경계 검증 |
| `DashboardWebSocketIntegrationTest.java` | 이동한 interceptor 이름 반영 및 기존 dashboard 회귀 검증 |

프런트는 이미 `/topic/dashboard`와 `/user/queue/rag-answer`만 정확히 구독하고 client SEND를 사용하지
않으므로 변경하지 않는다.

## 7. 검증 전략

### 단위 테스트

- ADMIN dashboard 정확한 구독 허용
- 인증 사용자 RAG 정확한 구독 허용
- 일반 사용자 dashboard와 Principal 없는 RAG 구독 거부
- pattern, 내부 queue, 알 수 없는 목적지, null 목적지 구독 거부
- 목적지와 무관하게 모든 client SEND 거부
- DISCONNECT 같은 비대상 command 통과

### 통합 테스트

- 두 사용자가 정확한 RAG 목적지를 구독해도 대상 사용자만 이벤트 수신
- `/topic/**`, `/queue/*` pattern SUBSCRIBE 거부
- 다른 사용자 RAG queue로 client SEND 시도 시 연결 거부 및 미전달
- 기존 dashboard 정확한 ADMIN 구독, 일반 사용자 거부, client SEND 거부 유지
- #356의 CONNECT·STOMP 인증과 HTTP 인증 회귀 없음

## 8. 영향과 남은 범위

- 거부된 SUBSCRIBE·SEND는 현재 STOMP 오류 처리에 따라 socket 전체가 닫힐 수 있다. 두 프런트는
  `onclose` 뒤 REST polling으로 전환하므로 기능은 유지된다.
- 새 destination은 허용 목록을 명시적으로 확장하기 전까지 거부된다. 이는 누락된 인가 정책으로 새
  채널이 열리는 것을 막기 위한 의도된 기본값이다.
- 연결 뒤 역할 변경, token 만료, 로그아웃을 기존 session에 즉시 반영하는 lifecycle 문제는 남는다.
