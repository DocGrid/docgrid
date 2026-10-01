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
- 테스트의 `RoleAuthorityService`와 `UserRoleRepository`는 `@MockitoBean`으로 대체하고,
  동일 사용자에 대해 `ADMIN → USER` 역할 전이를 결정적으로 제어했다. 따라서 **실제 DB 역할 변경 API,
  Redis 역할 캐시 무효화, GCP OpenProxy의 primary/standby 라우팅은 이번 테스트 대상이 아니다.**
- 자동 세션 재검증 간격을 테스트에서만 `1h`로 설정했다. 테스트가 재검증 메서드를 직접 호출해
  검사 **전**과 **후**를 구분하므로 스케줄러의 우연한 실행 시각에 결과가 좌우되지 않는다.
- 실제 운영 기본 재검증 간격은 코드의 `5s`이며, 이 테스트는 5초의 최대 지연을 실측한 결과가 아니다.

| 실행 위치 | 명령 | 결과 요약 | 해석 |
|---|---|---|---|
| 로컬 Mac | `open -a Docker` | Docker Desktop 시작 | 기존 컨테이너를 이용할 준비를 했다. |
| 로컬 Mac | `docker start docgrid-postgres17` | 기존 PostgreSQL 컨테이너 시작 | 새 DB나 GCP 자원을 만들지 않았다. Redis는 Docker 시작 시 이미 실행 중이었다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompSessionLifecycleIntegrationTest'` | 기존 통합 테스트 4건 통과 | 기존 세션 재검증 기준선을 확인했다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.auth.integration.StompDashboardRoleRevocationIntegrationTest'` | 신규 통합 테스트 3건 통과 | 관리자 대시보드의 세 경계를 각각 실제 WebSocket으로 확인했다. |
| 분리 작업 공간 | 새 경계 테스트·기존 세션 수명주기·대시보드 통합 테스트와 STOMP 단위 테스트 3개 클래스를 `--tests`로 함께 실행 | 총 49건, 실패·건너뜀 0건 | 기존 구독·push와 새 경계 테스트의 관련 회귀를 확인했다. |
| 분리 작업 공간 | `DB_PORT=55433 JWT_SECRET=<test-only-secret> ./backend/gradlew -p backend test` | JUnit XML 합계 207 suites, 1,249 tests, 실패·오류·건너뜀 0건; `BUILD SUCCESSFUL` | 전체 Backend 기본 테스트에 회귀가 없었다. Gradle에서 별도 태그로 제외한 성능·외부 E2E 시험까지 실행했다는 뜻은 아니다. |

## 시나리오별 실제 결과

| 경계 | 관측 결과 | 의미 |
|---|---|---|
| ADMIN 회수 **후 새 CONNECT** | 현재 역할이 `USER`인 새 세션의 `/topic/dashboard` SUBSCRIBE가 거부되고 브로커 구독이 등록되지 않았다. | 신규 연결은 이전 세션의 ADMIN Principal을 재사용하지 않았다. |
| 회수 **전 연결, 회수 후 새 SUBSCRIBE, 재검증 전** | 기존 세션에 저장된 ADMIN Principal로 구독이 실제 브로커에 등록됐다. | 현재 구현은 회수 직후의 새 구독을 즉시 차단하지 않는다. |
| 위 기존 연결의 **재검증 후** | 재검증이 역할 차이를 찾아 물리 세션을 닫고 브로커 구독을 제거했다. | 회수 반영은 세션 재검증을 거친 뒤에 성립한다. |
| 회수 전 등록된 구독의 **재검증 전 push** | 대시보드 메시지 1건이 실제 클라이언트에 도착했다. | 검사 간격에는 기존 구독으로 관리자 정보가 전달될 수 있다. |
| 같은 구독의 **재검증 후 push** | 세션 제거 후 새로 보낸 메시지는 수신되지 않았다. | 검사 완료 이후에는 해당 세션의 추가 전달이 중단됐다. |
| 역할 변경 없는 정상 ADMIN 회귀 | 기존 `DashboardWebSocketIntegrationTest`의 관리자 구독·push가 통과했다. | 정상 관리자 경로가 이번 테스트 추가로 변하지 않았다. |

## 결론과 주장할 수 없는 범위

이번 결과는 **주기적 회수**가 동작한다는 증거이면서, **즉시 회수는 제공하지 않는다**는 반례다.
테스트에서 재검증 전 새 구독과 기존 구독 push가 모두 허용됐다. 따라서 “역할 회수 API의 성공 응답
이후 관리자 WebSocket 메시지 0건”이라고 주장하면 안 된다.

`5s`는 기본 스케줄 간격이지 검증된 최악 지연 상한이 아니다. 스케줄러 지연, Redis·DB 응답 시간,
메시지 전달 경합은 별도 계측이 필요하다. 이 테스트는 로컬 통합 테스트이므로 GCP 3노드 복제 지연,
OpenProxy 라우팅, 실제 DB 커밋 뒤의 역할 조회 일관성을 증명하지 않는다. 즉시 회수가 제품 요구사항이면
권한 변경 시 열린 세션을 능동적으로 종료하거나, SUBSCRIBE와 push 경계에서 현재 권한을 재확인하는
별도 수정 설계가 필요하다.
