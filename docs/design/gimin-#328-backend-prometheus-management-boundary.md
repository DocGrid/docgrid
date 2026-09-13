# Backend Prometheus·Management 경계 설계

- 관련 이슈: #328
- 기준 브랜치: `develop`
- 대상: Spring Backend 운영 엔드포인트

## 문제

Backend에는 Prometheus가 읽을 표준 메트릭 엔드포인트가 없었다. Actuator를 기본 설정으로만
추가하면 사용자 API와 운영 엔드포인트가 같은 포트에 섞이고, 노출 설정이 넓어질 때 환경 변수나
Bean 설정 같은 운영 정보가 외부에 공개될 수 있다.

또한 Redis는 로그아웃 토큰 조회가 실패해도 핵심 요청을 계속 처리하는 fail-open 의존성이다.
Redis를 readiness에 포함하면 Redis 장애가 Backend 전체를 트래픽 대상에서 제거해 기존 장애 대응
정책과 충돌한다.

## 설계

### 포트와 노출 범위

- 사용자 API는 기존 `8080`을 유지한다.
- 운영 엔드포인트는 `${MANAGEMENT_PORT:8081}`에서 제공한다.
- HTTP로 노출하는 Actuator endpoint는 `health`, `prometheus` 두 개로 제한한다.
- Actuator discovery endpoint를 비활성화하고 health 상세 구성 요소를 응답에서 숨긴다.

별도 포트는 운영 트래픽을 구분하는 경계다. 실제 배포에서는 방화벽, Security Group, Compose
network 등으로 Management 포트의 접근 주체를 Prometheus와 운영자에게 제한해야 한다. 이 네트워크
연결은 Backend scrape job을 추가하는 후속 작업에서 구성한다.

### 두 SecurityFilterChain의 책임

`ManagementEndpointSecurityConfig`는 우선순위 1로 모든 Actuator endpoint를 먼저 판별한다.
health와 prometheus는 수집기와 probe가 토큰 없이 호출할 수 있게 허용하고, 그 밖의 endpoint는
기본 거부한다. 따라서 exposure 목록이 나중에 잘못 넓어져도 바로 공개되지 않는다.

기존 `SecurityConfig`는 우선순위 2에서 일반 API의 JWT, MCP API Key, 역할 인가를 그대로 담당한다.
두 체인이 경로 책임을 나누므로 Prometheus에 애플리케이션 JWT를 발급할 필요가 없고 기존 사용자 API
인증 규칙도 약해지지 않는다.

### Health group

| 경로 | 포함 항목 | 판단 대상 |
|---|---|---|
| `/actuator/health/liveness` | `livenessState` | 프로세스가 살아 있는가 |
| `/actuator/health/readiness` | `readinessState`, `db` | 요청 처리 상태이며 핵심 DB를 사용할 수 있는가 |
| `/actuator/health` | 등록된 전체 HealthContributor | 운영 진단용 전체 상태 |

Redis 장애는 전체 health에는 반영되지만 readiness에서는 제외한다. 이 방식이면 운영자는 Redis 이상을
관찰할 수 있으면서도 fail-open 정책에 따라 Backend가 계속 요청을 받는다. Redis 전용 경보는 도메인
메트릭과 경보 규칙을 추가하는 후속 작업에서 다룬다.

## 검증 경계

실제 HTTP 서버를 애플리케이션 포트와 Management 포트로 각각 띄워 공개 범위와 상태 코드를 검증한다.
테스트 전용 H2 DataSource로 DB health와 HikariCP 자동 계측을 활성화하고, 연결할 수 없는 Redis 주소로
전체 health와 readiness의 차이를 재현한다. JVM, HTTP 서버, HikariCP 메트릭이 Prometheus text
format에 포함되는지도 함께 확인한다.

테스트 전체에서는 Management 포트를 `0`으로 설정해 여러 Spring Context가 OS가 배정한 빈 포트를
사용하게 한다. 운영 기본 포트 `8081`의 의미는 유지하면서 테스트 Context 간 포트 충돌만 제거한다.

## 범위 밖

- Prometheus의 Backend scrape job과 컨테이너 네트워크 연결
- Job, RAG, Outbox 커스텀 메트릭
- Prometheus alert rule과 Alertmanager 연동
- Slack, Discord, 이메일 등 실제 수신 채널 설정
