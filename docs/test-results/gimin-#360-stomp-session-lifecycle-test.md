# 열린 STOMP 세션 인증 상태 재검증 테스트 결과 (#360)

## 1. 검증 목적

- CONNECT 뒤 로그아웃, JWT 만료, 역할 변경이 기존 물리 WebSocket 연결을 종료하는지 확인한다.
- 상태가 바뀌지 않은 정상 세션이 반복 검사 뒤에도 유지되고 RAG push를 받는지 확인한다.
- 세션 수만큼 Redis·DB를 호출하지 않고 설정한 batch 단위로 조회하는지 확인한다.
- Redis·DB 검증 실패 시 열린 연결도 fail-closed되는지 확인한다.
- 기존 연결 인증, 목적지 인가, HTTP JWT 인증과 역할 변경 경로에 회귀가 없는지 확인한다.

## 2. 실행 환경

| 항목 | 값 |
|---|---|
| 실행 일시 | 2026-09-28 KST |
| branch | `fix/360` |
| Spring Boot | 3.5.16 |
| Spring Messaging | 6.2.19 |
| PostgreSQL | `docgrid-postgres17`, host port `55433` |
| Redis | `docgrid-redis`, host port `6379` |
| profile | `test` |

JWT secret은 실행 환경에만 test 전용 값으로 주입했고 저장소에는 기록하지 않았다. 통합 테스트의 재검증
간격은 빠른 검증을 위해 `100ms`로 덮어썼다.

## 3. 변경 전 실패 재현

실제 WebSocket으로 연결한 뒤 같은 JWT의 jti를 blacklist에 등록하고 `SimpUserRegistry`에서 사용자가
사라질 때까지 최대 3초를 기다리는 테스트를 생산 코드 변경 전에 실행했다.

```text
1 test completed, 1 failed
ConditionTimeoutException: condition was not fulfilled within 3 seconds
```

신규 CONNECT 차단은 이미 동작했지만, blacklist 등록 뒤 기존 세션을 닫는 경로가 없어 timeout이 발생했다.
이는 mock 호출만 검사한 결과가 아니라 실제 transport·STOMP broker를 통과한 재현이다.

## 4. 단위 테스트

| 테스트 | 건수 | 검증 내용 | 결과 |
|---|---:|---|---|
| `StompSessionRegistryTest` | 5 | 등록·인증 결합, 정상 종료, 이미 닫힌 세션, 종료 실패 재시도 | PASS |
| `StompSessionRevalidationSchedulerTest` | 7 | 만료, blacklist, 역할 변경, batch 조회, Redis·DB failure, Gauge | PASS |
| `StompAuthChannelInterceptorTest` | 11 | snapshot 필수 값과 registry 결합을 포함한 CONNECT 인증 | PASS |
| `TokenBlacklistServiceTest` | 5 | MGET 매핑, 중복 제거, 빈 입력, 불완전 응답 | PASS |
| 합계 | 28 |  | PASS |

종료 Counter의 reason과 활성 session Gauge도 단위 테스트에서 함께 검증했다.

## 5. 실제 WebSocket 통합 테스트

```bash
JWT_SECRET={test-only-secret} DB_PORT=55433 \
  ./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.auth.integration.StompSessionLifecycleIntegrationTest'
```

```text
4 tests completed, 0 failed
BUILD SUCCESSFUL in 10s
```

| 시나리오 | 실제 결과 |
|---|---|
| 연결한 token의 jti를 Redis blacklist에 등록 | 3초 안에 사용자 session 제거, 이후 RAG event 미수신 |
| 1초 수명의 JWT로 연결 | 만료 뒤 session 제거 |
| 연결 당시 역할을 DB 기준으로 변경 | 오래된 Principal을 가진 session 제거 |
| 인증 상태를 유지한 채 여러 `100ms` 검사 주기 경과 | session 유지, 대상 RAG event 수신 |

## 6. 일괄 조회와 주요 회귀 테스트

batch size보다 많은 서로 다른 jti·userId를 만든 단위 테스트에서 다음 호출 수를 확인했다.

```text
Redis 호출 = ceil(고유 jti 수 / batch-size)
DB 호출    = ceil(blacklist 제외 고유 userId 수 / batch-size)
```

blacklist로 종료된 세션은 DB 역할 조회에서 제외된다. 개별 세션마다 `hasKey`와 역할 query를 실행하는
N+1 호출은 발생하지 않는다.

다음 관련 테스트 10개 클래스를 함께 검증했고 전체 테스트 XML에서도 같은 결과를 다시 확인했다.

| 범위 | 건수 | 결과 |
|---|---:|---|
| session registry·재검증·CONNECT·blacklist 단위 | 28 | PASS |
| session lifecycle 실제 WebSocket | 4 | PASS |
| 기존 STOMP 연결 인증 | 4 | PASS |
| 기존 STOMP 목적지 인가 | 5 | PASS |
| dashboard WebSocket | 4 | PASS |
| HTTP JWT 인증 | 3 | PASS |
| 역할 변경 command | 5 | PASS |
| 합계 | 53 | PASS |

## 7. 전체 Backend 검증과 테스트 인프라 보정

첫 전체 실행은 다음 결과로 실패했다.

```text
1225 tests completed, 5 failed
org.postgresql.util.PSQLException: FATAL: sorry, too many clients already
```

5건 모두 assertion 실패가 아니라 Flyway 초기화 전에 PostgreSQL 연결을 만들지 못한 context 초기화
실패였다. 전체 테스트가 여러 Spring context의 Hikari pool을 보관하는 상황에서 새 WebSocket 통합 테스트
context가 기본 최대 10개 연결을 추가한 것이 원인이었다.

제품 설정은 바꾸지 않고 새 테스트 context에만 다음 상한을 적용했다.

```text
spring.datasource.hikari.maximum-pool-size=2
spring.datasource.hikari.minimum-idle=0
```

통합 테스트를 다시 통과한 뒤 전체 Backend 테스트를 재실행했다.

```bash
JWT_SECRET={test-only-secret} DB_PORT=55433 \
  ./backend/gradlew -p backend test
```

Gradle XML test report 합산 결과:

```text
1236 tests completed, 0 failed, 0 skipped
BUILD SUCCESSFUL in 1m 7s
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

- physical session을 CONNECT 전에 등록하고 인증 완료 뒤 snapshot을 결합해 추적에서 빠지는 인증 세션을
  만들지 않는다.
- JWT 원문·이메일을 registry와 로그에 저장하지 않는다.
- 만료를 먼저 처리하고 blacklist 세션을 역할 조회에서 제외해 불필요한 외부 호출을 줄인다.
- 역할 비교는 `Set`으로 수행해 DB 정렬이나 중복에 따라 정상 세션이 닫히지 않는다.
- Redis·DB 오류는 기존 세션에도 fail-closed를 적용하고, 개별 `close()` 실패는 다음 주기에 재시도한다.
- 각 Backend가 자신이 가진 물리 연결만 닫으므로 여러 인스턴스에서 scheduler lock이 필요하지 않다.
- 정상 세션 유지와 실제 push 수신을 함께 검증해 scheduler가 모든 연결을 잘못 닫는 회귀를 방지한다.

남은 제한은 blacklist 기록 자체의 실패·Redis persistence, 검사 간격만큼의 반영 지연, 인증 전 물리
연결 제한이다. 이들은 현재 세션 재검증이 해결하는 범위와 분리된 운영·전송 계층 문제다.
