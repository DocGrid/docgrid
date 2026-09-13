# DocGrid Prometheus

DocGrid가 함께 제공하는 Prometheus는 Host에서 실행한 Spring Backend와 Docker Compose의 Embedding
Provider를 수집한다. 기본 scrape 주기와 rule 평가 주기는 15초다.

## 번들 Prometheus 사용

Backend를 먼저 실행한다.

```bash
./backend/gradlew -p backend bootRun
```

Management endpoint는 기본적으로 Host의 `8081` 포트에서 열린다. 그다음 monitoring profile을
실행한다.

```bash
docker compose --profile monitoring up -d prometheus
```

Prometheus는 컨테이너에서 `host.docker.internal:8081`을 수집한다. Compose의 `extra_hosts`가
Linux의 host gateway를 같은 이름으로 연결하며 Docker Desktop도 같은 주소를 지원한다.

- Target 상태: <http://localhost:9090/targets>
- Alert 상태: <http://localhost:9090/alerts>

기본 Backend target과 배포 식별 label은
`monitoring/prometheus/targets/docgrid-backend.yml`에서 변경한다.

```yaml
- targets:
    - host.docker.internal:8081
  labels:
    environment: local
    cluster: docgrid-local
```

## 기존 Prometheus 사용

기존 Prometheus를 운영하는 환경에서는 다음 scrape job을 해당 Prometheus 설정에 추가한다.

```yaml
scrape_configs:
  - job_name: docgrid-backend
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - docgrid-backend.internal:8081
        labels:
          environment: production
          cluster: docgrid-production
```

`8081`은 사용자 API 포트가 아닌 Management 포트다. 방화벽, Security Group, 컨테이너 network로
Prometheus와 운영자만 접근하도록 제한한다.

Backend 경보를 함께 사용하려면 `monitoring/prometheus/rules/docgrid-backend-alerts.yml`을 기존
Prometheus의 `rule_files` 경로에 복사한다.

## 기본 Backend 경보

| 경보 | 조건 | 지속 시간 |
|---|---|---:|
| `DocGridBackendDown` | Backend scrape 실패 | 1분 |
| `DocGridDatabasePoolSaturated` | HikariCP active/max가 90% 초과 | 2분 |
| `DocGridBackendHighServerErrorRatio` | 5분간 20건 이상이며 5xx가 5% 초과 | 3분 |

HTTP 오류율에서는 Streamable HTTP 특성이 다른 `/mcp`를 제외한다. 이 경보들은 현재 Prometheus
화면에서 확인하며 외부 전달은 Alertmanager 설정을 추가한 뒤 활성화된다.

## 설정 검증

로컬에 promtool을 설치하지 않아도 고정된 Prometheus 이미지로 검사할 수 있다.

```bash
docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  check config /etc/prometheus/prometheus.yml

docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  check rules \
  /etc/prometheus/rules/embedding-provider-alerts.yml \
  /etc/prometheus/rules/docgrid-backend-alerts.yml

docker run --rm --entrypoint=promtool \
  -v "$PWD/monitoring/prometheus:/etc/prometheus:ro" \
  prom/prometheus:v3.5.5 \
  test rules /etc/prometheus/tests/docgrid-backend-alerts.test.yml

docker compose config
```
