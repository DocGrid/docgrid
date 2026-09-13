# Prometheus Backend 수집과 가용성 경보 설계

- 관련 이슈: #330
- 선행 조건: #328의 Backend Management endpoint
- 대상: 번들 Prometheus와 기존 Prometheus 사용 환경

## 문제

Backend가 `/actuator/prometheus`를 제공해도 Prometheus scrape job이 없으면 시계열은 저장되지 않는다.
현재 번들 Prometheus는 Docker network 안의 Embedding Provider만 수집하며 Spring Backend는 Host에서
`bootRun`으로 실행한다. 컨테이너에서 Host Management 포트로 접근하는 경로와 Backend의 기본
가용성 경보가 필요하다.

## 수집 경계

번들 Prometheus는 `file_sd_configs`로
`monitoring/prometheus/targets/docgrid-backend.yml`을 읽는다. 기본 target은
`host.docker.internal:8081`이며 `environment=local`, `cluster=docgrid-local` label을 붙인다.

Compose의 `extra_hosts`는 Linux에서 `host.docker.internal`을 Host gateway로 연결한다. Docker
Desktop도 같은 이름을 지원하므로 운영체제에 따라 Prometheus 본체 설정을 나누지 않는다. 다른
Host·Port나 production label은 target 파일에서만 변경한다.

기존 Prometheus를 사용하는 환경은 같은 `job=docgrid-backend`와
`metrics_path=/actuator/prometheus` 계약을 사용한다. Management 포트의 네트워크 접근 제한은 배포
환경의 방화벽, Security Group, 컨테이너 network가 담당한다.

## 경보

| 경보 | 조건 | `for` | 목적 |
|---|---|---:|---|
| `DocGridBackendDown` | `up == 0` | 1분 | 일시적인 scrape 실패와 실제 중단 구분 |
| `DocGridDatabasePoolSaturated` | HikariCP active/max > 90% | 2분 | 지속적인 DB pool 고갈 조기 감지 |
| `DocGridBackendHighServerErrorRatio` | 5분간 20건 이상, 5xx > 5% | 3분 | 저트래픽 단일 오류의 오탐 방지 |

모든 경보에는 `service=docgrid-backend`와 severity를 붙인다. Backend target의 `cluster`와
`environment` label은 경보 시계열에 유지돼 이후 Alertmanager routing에 사용할 수 있다.

HTTP 오류율은 `sum by (cluster, environment)`로 인스턴스 전체를 집계한다. 분자와 분모 모두
`uri!="/mcp"`를 사용한다. MCP Streamable HTTP 요청은 일반 REST와 응답 특성이 달라 별도 오류
예산 없이 일반 API 경보에 합치지 않는다.

## 검증 전략

`promtool check config`와 `check rules`로 syntax와 rule loading을 확인한다. `promtool test rules`는
다음 네 경계를 고정한다.

1. Backend down이 1분 전에는 firing하지 않는다.
2. HikariCP 포화가 2분 유지돼야 firing한다.
3. 5xx 비율이 높아도 최소 요청량을 만족한 cluster만 경보한다.
4. `/mcp` 5xx는 일반 Backend 오류율 경보를 만들지 않는다.

실제 E2E에서는 Host Backend와 격리 Prometheus를 기동하고 Embedding Provider와 Backend가 모두
`UP`인지 확인한다. Backend를 중단해 target `DOWN`, 경보 `pending`, `firing`을 순서대로 확인한 뒤
재기동해 target `UP`과 경보 `inactive` 복구까지 검증한다.

## 운영 경계

현재 경보는 Prometheus에서만 평가하고 화면에서 확인한다. Alertmanager 전달, 알림 그룹화, silence,
외부 채널은 후속 작업에서 추가한다. Job·RAG·Outbox의 도메인 상태와 Queue 정체도 이 변경의
기본 인프라 경보에는 포함하지 않는다.
