# STOMP 관리자 권한 회수 경계 검증 결과 (#365)

## 목적과 기준

[#360](https://github.com/DocGrid/docgrid/issues/360)은 열린 STOMP 세션의 인증 상태를 주기적으로
재검증해 달라진 역할의 물리 연결을 종료한다. 이번 테스트는 관리자 대시보드에서 권한 회수 시점을
기준으로 **새 연결**, **기존 연결의 새 구독**, **이미 등록된 구독의 push**를 분리해 확인한다.
HTTP `/admin/**` 권한 확인([#364](https://github.com/DocGrid/docgrid/pull/364))과는 다른 경계다.

검증 시작점은 `origin/develop`의 `2da1eec`이다. 실행일은 2026-10-01 KST이고, 작업 브랜치는
`test/365`이다. 이번 변경에는 제품 코드가 없다.

## 실행 환경과 방법

- 로컬 Docker Desktop에서 기존 PostgreSQL 17/pgvector 컨테이너와 Redis 컨테이너를 사용했다.
- Spring `test` 프로필의 실제 WebSocket transport와 STOMP broker를 사용했다.
- 기존 세 경계 테스트는 `RoleAuthorityService`와 `UserRoleRepository`를 `@MockitoBean`으로 대체해
  `ADMIN → USER` 전이를 결정적으로 제어했다. 추가한 실제 경로 테스트는 이 두 컴포넌트를 대체하지
  않고 별도 테스트 사용자를 만들어 HTTP 역할 회수 API → PostgreSQL 커밋 → Redis 캐시 무효화 →
  WebSocket 재검증을 연결했다. **GCP OpenProxy의 primary/standby 라우팅은 시험하지 않았다.**
- 자동 세션 재검증 간격을 테스트에서만 `1h`로 설정했다. 테스트가 재검증 메서드를 직접 호출해
  검사 **전**과 **후**를 구분하므로 스케줄러의 우연한 실행 시각에 결과가 좌우되지 않는다.
- 실제 운영 기본 재검증 간격은 코드의 `5s`이며, 이 테스트는 5초의 최대 지연을 실측한 결과가 아니다.

| 실행 위치 | 명령 | 결과 요약 | 해석 |
|---|---|---|---|
| 로컬 Mac | `open -a Docker` | Docker Desktop 시작 | 기존 컨테이너를 이용할 준비를 했다. |
| 로컬 Mac | `docker start docgrid-postgres17` | 기존 PostgreSQL 컨테이너 시작 | 새 DB나 GCP 자원을 만들지 않았다. Redis는 Docker 시작 시 이미 실행 중이었다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompSessionLifecycleIntegrationTest'` | 기존 통합 테스트 4건 통과 | 기존 세션 재검증 기준선을 확인했다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompDashboardRoleRevocationIntegrationTest'` | 신규 통합 테스트 3건 통과 | 관리자 대시보드의 세 경계를 각각 실제 WebSocket으로 확인했다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompDashboardRealRoleRevocationIntegrationTest'` | 실제 DB·Redis 통합 테스트 1건 통과 | `DELETE /admin/users/{id}/roles/ADMIN`의 커밋·캐시 무효화와 새·기존 STOMP 세션 동작을 연결했다. |
| 로컬 PostgreSQL 컨테이너 | `psql -U app -d app -tAc "SELECT COUNT(*) FROM docgrid_test.users WHERE email LIKE 'stomp-real-revoke-%@example.test'"` | `0` | 추가 테스트가 만든 사용자가 DB에 남지 않았다. |
| 분리 작업 공간 | 새 경계 테스트·기존 세션 수명주기·대시보드 통합 테스트와 STOMP 단위 테스트 3개 클래스를 `--tests`로 함께 실행 | 총 49건, 실패·건너뜀 0건 | 기존 구독·push와 새 경계 테스트의 관련 회귀를 확인했다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test` 첫 실행 | 1,250건 중 기존 `StompSessionLifecycleIntegrationTest`의 1초 JWT 만료 사례 1건 연결 실패 | 새 테스트는 통과했지만 전체 실행은 실패했다. 원인을 확정하지 않고 아래처럼 재실행했다. |
| 분리 작업 공간 | 실패한 기존 클래스와 신규 실제 DB·Redis 클래스만 `--tests`로 함께 실행 | 5건 통과, `BUILD SUCCESSFUL` | 첫 실패가 이 두 클래스 조합에서는 재현되지 않았다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test` 재실행 | JUnit XML 합계 208 suites, 1,250 tests, 실패·오류·건너뜀 0건; `BUILD SUCCESSFUL` | 전체 Backend 기본 테스트가 재실행에서 통과했다. 성능·외부 E2E 시험까지 실행했다는 뜻은 아니다. |

## 시나리오별 실제 결과

| 경계 | 관측 결과 | 의미 |
|---|---|---|
| ADMIN 회수 **후 새 CONNECT** | 현재 역할이 `USER`인 새 세션의 `/topic/dashboard` SUBSCRIBE가 거부되고 브로커 구독이 등록되지 않았다. | 신규 연결은 이전 세션의 ADMIN Principal을 재사용하지 않았다. |
| 회수 **전 연결, 회수 후 새 SUBSCRIBE, 재검증 전** | 기존 세션에 저장된 ADMIN Principal로 구독이 실제 브로커에 등록됐다. | 현재 구현은 회수 직후의 새 구독을 즉시 차단하지 않는다. |
| 위 기존 연결의 **재검증 후** | 재검증이 역할 차이를 찾아 물리 세션을 닫고 브로커 구독을 제거했다. | 회수 반영은 세션 재검증을 거친 뒤에 성립한다. |
| 회수 전 등록된 구독의 **재검증 전 push** | 대시보드 메시지 1건이 실제 클라이언트에 도착했다. | 검사 간격에는 기존 구독으로 관리자 정보가 전달될 수 있다. |
| 같은 구독의 **재검증 후 push** | 세션 제거 후 새로 보낸 메시지는 수신되지 않았다. | 검사 완료 이후에는 해당 세션의 추가 전달이 중단됐다. |
| 역할 변경 없는 정상 ADMIN 회귀 | 기존 `DashboardWebSocketIntegrationTest`의 관리자 구독·push가 통과했다. | 정상 관리자 경로가 이번 테스트 추가로 변하지 않았다. |

## 실제 DB·Redis 역할 회수 경로

`StompDashboardRealRoleRevocationIntegrationTest`는 관리자 호출자와 대상 사용자만 새로 만들고,
기존 `ADMIN` 역할을 두 사용자에게 부여했다. 대상의 기존 STOMP 세션을 연결·구독한 다음 Redis의
`auth:roles:{userId}` 캐시에 `ADMIN`이 저장된 것을 확인했다. 이어 관리자 호출자의 JWT로 실제
`DELETE /admin/users/{targetId}/roles/ADMIN`을 요청했다.

| 순서 | 직접 확인한 결과 | 해석 |
|---|---|---|
| 역할 회수 응답 | HTTP `200 OK` | 테스트 대체 객체가 아닌 실제 관리자 API를 통과했다. |
| PostgreSQL | 대상의 `ADMIN` 매핑이 존재하지 않음 | 회수 트랜잭션의 DB 변경이 반영됐다. |
| Redis | 역할 캐시 키 삭제, 대상 세대 키 값 `1` | 커밋 후 무효화가 실행됐다. 새 연결의 재조회 결과에도 `ADMIN`이 다시 캐시되지 않았다. |
| 재검증 전 | 기존 구독에 dashboard push 1건 도착, 회수 후 새 연결의 관리자 SUBSCRIBE 거부 | 새 구독과 이미 열린 구독의 권한 적용 시점이 다르다. |
| 재검증 후 | 기존 세션·구독 제거, 이후 push 0건 수신 | DB 역할을 다시 읽는 주기 검사가 뒤늦게 열린 세션을 정리한다. |

자동 재검증은 테스트에서만 `1h`로 늦추고 메서드를 직접 호출했다. 따라서 이 결과는
회수 직후와 재검증 후의 **동작 경계**를 증명하지, 운영 기본값 `5s`에서의 실제 노출 시간이나
최악 지연을 측정하지 않는다. 테스트가 만든 사용자·매핑과 대상 Redis 키는 종료 시 제거한다.
전체 테스트의 첫 실행에서는 기존 JWT 만료 테스트가 STOMP 연결 도중 종료됐다. 그 테스트는
유효기간 `1s` 토큰으로 연결하기 때문에 시각에 민감할 가능성이 있지만, 이번 기록만으로
실패 원인을 단정할 수 없다. 해당 클래스와 새 테스트를 함께 돌린 재실행, 전체 재실행은 모두 통과했다.

## 결론과 주장할 수 없는 범위

이번 결과는 **주기적 회수**가 동작한다는 증거이면서, **즉시 회수는 제공하지 않는다**는 반례다.
테스트에서 재검증 전 새 구독과 기존 구독 push가 모두 허용됐다. 따라서 “역할 회수 API의 성공 응답
이후 관리자 WebSocket 메시지 0건”이라고 주장하면 안 된다.

`5s`는 기본 스케줄 간격이지 검증된 최악 지연 상한이 아니다. 스케줄러 지연, Redis·DB 응답 시간,
메시지 전달 경합은 별도 계측이 필요하다. 실제 DB 커밋·Redis 무효화 경로는 로컬에서 검증했지만,
GCP 3노드 복제 지연과 OpenProxy 라우팅에서 같은 결과가 나온다는 증거는 아니다. 즉시 회수가 제품 요구사항이면
권한 변경 시 열린 세션을 능동적으로 종료하거나, SUBSCRIBE와 push 경계에서 현재 권한을 재확인하는
별도 수정 설계가 필요하다.
