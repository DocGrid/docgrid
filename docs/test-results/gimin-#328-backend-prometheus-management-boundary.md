# Backend Prometheus·Management 경계 검증 결과

- 관련 이슈: #328
- 실행 일시: 2026-09-13 (Asia/Seoul)
- 수정 전 기준 Commit: `ac59f06` (`develop`)
- 환경: Java 17, Spring Boot 3.5.16, Micrometer 1.15.12, H2 2.3.232

## 검증 목적

- Prometheus와 health probe가 별도 Management 포트에서 인증 없이 동작해야 한다.
- 민감한 Actuator endpoint와 애플리케이션 포트에서는 운영 endpoint를 제공하지 않아야 한다.
- fail-open Redis가 고장 나도 DB가 정상이면 readiness는 `UP`이어야 한다.
- JVM, HTTP 서버, HikariCP 자동 계측이 Prometheus 형식으로 출력돼야 한다.
- 기존 JWT·MCP 보안과 Backend 전체 테스트가 회귀하지 않아야 한다.

## 수정 전 상태

기준 Commit에는 Actuator starter와 Prometheus registry가 없었고 `management.*` 설정도 없었다.
따라서 Backend 자체에서 `/actuator/prometheus`와 health probe endpoint를 제공할 수 없었다.

## HTTP 경계 검증

`ManagementEndpointIntegrationTest`는 애플리케이션 서버와 Management 서버를 서로 다른 임의 포트로
기동했다. H2 DB는 정상 상태로 두고 Redis는 연결할 수 없는 `127.0.0.1:1`로 설정했다.

| 요청 | 결과 | 검증 의미 |
|---|---:|---|
| Management `/actuator/health/liveness` | 200 | liveness 공개 |
| Management `/actuator/health/readiness` | 200, `UP` | DB 기반 readiness 공개 |
| Management `/actuator/prometheus` | 200 | Prometheus scrape 가능 |
| Management `/actuator/health` | 503, `DOWN` | Redis 장애가 전체 health에는 반영됨 |
| Management `/actuator/env` | 404 | 민감 endpoint 비노출 |
| Management `/actuator/configprops` | 404 | 민감 endpoint 비노출 |
| Application `/actuator/prometheus` | 404 | 운영 endpoint가 사용자 API 포트에 없음 |

전체 health가 Redis 장애를 감지해 `DOWN`인 같은 시점에도 readiness는 `UP`이었다. 따라서 Redis를
관찰 대상에서는 유지하면서 트래픽 수신 조건에서는 제외한다는 설계가 실제 HTTP 응답으로 확인됐다.

배포 JAR도 PostgreSQL 17.8과 Redis를 연결해 애플리케이션 `18080`, Management `18081` 포트로
직접 기동했다. 두 SecurityFilterChain이 함께 등록된 전체 애플리케이션에서 liveness, readiness,
prometheus는 각각 200을 반환했다. 인증하지 않은 Management `/actuator/env`와 애플리케이션
`/actuator/prometheus`는 기존 애플리케이션 보안 체인의 최종 거부 규칙에 의해 401을 반환했으며
운영 데이터는 노출되지 않았다.

## Prometheus 출력 검증

한 번의 scrape 응답에서 다음 자동 계측 시계열을 확인했다.

| 영역 | 확인한 시계열 |
|---|---|
| JVM | `jvm_memory_used_bytes` |
| HTTP 서버 | `http_server_requests_seconds_count` |
| DB Connection Pool | `hikaricp_connections` |

테스트에서는 Spring Boot 테스트가 기본으로 Metrics export를 끄는 동작을
`@AutoConfigureObservability`로 해제해 운영과 같은 registry·endpoint 자동 설정을 검증했다.

## 실행 명령과 결과

Management 경계와 관련 보안 단위 테스트를 먼저 실행했다.

```bash
./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.global.config.ManagementEndpointIntegrationTest' \
  --tests 'com.opensource.docgrid.global.exception.SecurityErrorResponseTest' \
  --tests 'com.opensource.docgrid.domain.auth.jwt.*' \
  --tests 'com.opensource.docgrid.domain.mcp.security.*'
```

- 결과: 성공

실제 PostgreSQL 17.8을 연결해 전체 테스트와 배포 Artifact 빌드를 실행했다.

```bash
DB_PORT=55433 \
JWT_SECRET=test-only-management-secret-key-at-least-thirty-two-bytes \
./backend/gradlew -p backend build
```

- 결과: 1,152 tests, 0 failed, 0 errors, 0 skipped
- Gradle 결과: `BUILD SUCCESSFUL in 45s`
