# Prometheus Backend 수집과 가용성 경보 검증 결과

- 관련 이슈: #330
- 실행 일시: 2026-09-13 (Asia/Seoul)
- 수정 전 기준 Commit: `9b0f701` (`develop`)
- 환경: Prometheus·promtool 3.5.5, Spring Boot 3.5.16, PostgreSQL 17.8, Docker Desktop

## 검증 목적

- Docker의 Prometheus가 Host에서 실행한 Backend Management 포트를 수집해야 한다.
- 기존 Embedding Provider target은 변경 후에도 정상이어야 한다.
- Backend 중단이 1분 동안 지속될 때만 `DocGridBackendDown`이 firing해야 한다.
- Backend 복구 뒤 target과 경보가 정상 상태로 돌아와야 한다.
- DB pool과 HTTP 5xx 규칙의 지속 시간, 최소 표본, MCP 제외 조건을 단위 테스트로 고정해야 한다.

## 설정과 규칙 검증

고정된 `prom/prometheus:v3.5.5` 이미지의 promtool을 사용했다.

| 검증 | 결과 |
|---|---|
| `promtool check config` | 성공, rule file 2개 로드 |
| `promtool check rules` | 성공, Backend 3개·Embedding 7개 규칙 |
| `promtool test rules` | 성공, 4개 시나리오 |
| `docker compose config` | 성공 |
| `docker compose --profile monitoring config` | 성공, host gateway와 target mount 포함 |

규칙 테스트는 Backend down 1분, HikariCP 포화 2분, 5xx 최소 20건과 5% 초과, `/mcp` 제외를
검증했다. 낮은 요청량 cluster는 같은 10% 오류율에서도 경보가 발생하지 않았다.

Prometheus 이미지의 기본 entrypoint가 `prometheus`이므로 문서의 검증 명령은
`--entrypoint=promtool`로 명시했다. 실제 컨테이너 실행으로 이 명령 형식까지 확인했다.

## 실제 수집

Backend는 Host의 `8081` Management 포트에서 실행하고, 검증용 Prometheus는 기존 인스턴스를
건드리지 않도록 `19090`에 격리했다. Prometheus 컨테이너는 기존 `docgrid_docgrid-local` network와
Host gateway를 함께 사용했다.

첫 scrape 이후 target 상태는 다음과 같았다.

```text
docgrid-backend     host.docker.internal:8081  local  docgrid-local  up
embedding-provider embedding-server:8000                               up
```

Backend target에는 target 파일의 `environment=local`, `cluster=docgrid-local` label이 적용됐고
기존 Embedding Provider도 계속 `UP`이었다.

## Backend 중단과 복구

| 시각 | 관찰 결과 |
|---|---|
| 17:36:36 | Backend 종료 완료 |
| 17:36:58 | `DocGridBackendDown` activeAt, 상태 `pending` |
| 17:37:03 | target `down`, `connection refused` 기록 |
| 17:38:32 | 경보 `firing`, 값 `0` 확인 |
| 17:38:45 | Backend 재기동 완료 |
| 17:39:03 | target `up`, scrape 오류 없음 |
| 17:39:22 | `DocGridBackendDown` 조회 결과 0건, `inactive` 확인 |

중단 후 첫 실패 scrape와 rule 평가 주기 안에 pending으로 들어갔고, activeAt부터 설정한 1분이 지난
뒤 firing했다. Backend 재기동 후 첫 성공 scrape와 다음 평가에서 경보가 사라졌다.

## 실행 명령

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
docker compose --profile monitoring config
```

격리 Prometheus와 Backend는 검증 후 종료했고, 테스트 전에 정지 상태였던 PostgreSQL도 다시
중지했다.
