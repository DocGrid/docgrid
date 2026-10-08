# DocGrid Grafana

DocGrid Grafana는 Prometheus에 저장된 Backend·Embedding Provider·비동기 Pipeline Metric을 한 화면에서
해석하는 읽기 전용 운영 Dashboard다. 경보의 평가와 외부 전송은 계속 Prometheus와 Alertmanager가
담당한다.

## 번들 Dashboard 실행

Backend Management Endpoint가 `localhost:8081`에서 실행 중인 상태로 세 Monitoring 서비스를
기동한다.

```bash
docker compose --profile monitoring up -d prometheus alertmanager grafana
```

- Grafana: <http://localhost:3000/d/docgrid-operations/docgrid-operations>
- Prometheus Target: <http://localhost:9090/targets>
- Prometheus Alert: <http://localhost:9090/alerts>
- Alertmanager: <http://localhost:9093>

Grafana는 Prometheus의 준비 여부에 의존하지 않고 시작한다. Prometheus가 나중에 준비되면
Provisioning된 Data Source가 다시 연결된다.

## 접근 경계

번들 Grafana는 `127.0.0.1`에만 Port를 공개한다. 초기 관리자 계정을 만들지 않고 익명 Viewer만
허용하므로 Dashboard를 조회할 수 있지만 UI에서 Data Source나 Dashboard를 변경할 수 없다. 설정은
Git에서 다음 파일을 수정한다.

- Data Source: `monitoring/grafana/provisioning/datasources/prometheus.yml`
- Dashboard Provider: `monitoring/grafana/provisioning/dashboards/docgrid.yml`
- Dashboard: `monitoring/grafana/dashboards/docgrid-operations.json`

공유 서버나 외부 Network에 배포할 때는 번들 접근 설정을 그대로 사용하지 않는다. 조직 Grafana의
인증·TLS·권한 정책을 적용하고 Dashboard JSON을 가져온다. 이때 Prometheus Data Source UID를
`docgrid-prometheus`로 만들거나 가져오기 과정에서 조직 Data Source로 교체한다.

## GCP OpenSQL HA 시연 Dashboard

`dashboards/docgrid-ha-demo.json`은 별도 GCP 관측 VM에서 사용하는 수동 가져오기용 Dashboard다.
번들 Data Source가 아니라 인증된 관측 VM의 Prometheus UID `ha-prometheus`를 요구한다. 앱 A/B,
Patroni 3노드, Hikari 대기, standby WAL 수신·재생 차이, k6 결과별 응답/초와 활성 VU를 표시한다.
`run_id` 변수를 선택해 한 실행만 볼 수 있다.

이 화면의 `up=1`은 지표 수집 성공일 뿐 HTTP 쓰기 성공이 아니다. OpenProxy 프로세스 준비 상태는
현재 Prometheus에 직접 수집하지 않으므로 운영자 장애 로그와 HTTP 원장을 함께 본다. 그래프만으로
성공 응답 데이터의 보존이나 RPO를 주장하지 않으며 요청 ID별 DB 대조 결과를 별도로 확인한다.
Remote Write의 누적 Trend p95/p99는 복구 시점 지연으로 오해하기 쉬워 패널에서 제외했다.
실행·장애 계측 결과는 [GCP HA 대시보드 검증 기록](../../docs/test-results/opensql-ha-live-dashboard-fault-rehearsal-20261009.md)에 둔다.

## 화면 구성

Dashboard는 `cluster`, `environment`, `instance` 순서로 조회 범위를 좁힌다. 기본값 `All`은 같은
Prometheus가 수집하는 모든 DocGrid 배포를 표시한다.

| Row | 확인하는 상태 | 주요 판단 |
|---|---|---|
| 전체 상태 | Backend·Provider Scrape, 발생 중 경보, Snapshot 나이 | 수집 중단과 실제 서비스 상태를 먼저 구분한다. |
| Embedding Queue | Claim 가능·지연 Retry·처리 중 Job, Worker, 대기 시간 | Worker 부재, Retry 대기, 처리 정체를 구분한다. |
| Embedding Circuit | OPEN/HALF_OPEN 보호, Probe 실패, 상태 전환 | Provider 요청 차단과 실제 회복 Probe 결과를 확인한다. |
| Embedding Provider | Model, 내부 Queue, Memory, 처리율, p99 | Circuit 원인이 Provider 자원·Queue·지연인지 확인한다. |
| RAG Queue | PROCESSING 수·나이, 완료 결과 | Provider Fallback과 Timeout Sweep 증가를 확인한다. |
| Sync Outbox | Claim 가능·처리 중 Event, 대기 시간, 처리 결과 | 재시도와 최종 실패를 확인한다. |
| Metric 수집 상태 | Snapshot 나이와 갱신 결과 | Dashboard 값 자체가 오래됐는지 확인한다. |

여러 Backend가 같은 DB를 수집하면 Queue Gauge가 인스턴스마다 같은 상태를 노출한다. Dashboard는
해당 값을 합산하지 않고 `max by (cluster)`로 표시한다. Counter 기반 Panel은 선택 시간 범위에 맞춘
`$__rate_interval`을 사용한다.

## 장애 해석 순서

1. 전체 상태에서 Backend·Provider Scrape와 현재 경보를 확인한다.
2. 운영 Snapshot 나이가 계속 증가하면 Queue 값이 최신이 아니므로 Snapshot 갱신 실패부터 조사한다.
3. Embedding Queue에서 Claim 가능 Job, 지연 Retry Job, 활성 Worker를 비교한다.
4. Circuit이 보호 중이면 Probe 실패와 최근 상태 전환을 확인한다.
5. Provider Row에서 Model, 대기 Queue, Memory, p99로 원인을 좁힌다.
6. 복구 후 Circuit과 Probe 실패가 `0`, 지연 Job이 감소하고 처리 성공 Counter가 증가하는지 확인한다.

Circuit 경보의 정확한 시간 창과 Queue 경보 기준은
[Prometheus 가이드](../prometheus/README.md)를 따른다.

## 설정 검증

정적 검증은 Dashboard 구조, 필수 Row·변수·Metric, Panel ID·배치, Data Source 참조를 확인한다.

```bash
python3 monitoring/grafana/validate_dashboard.py
```

격리 E2E는 Fixture Metric을 실제 Prometheus가 Scrape하게 하고 Grafana File Provisioning을 거쳐
Dashboard의 모든 PromQL을 실행한다. 실행마다 동적 Loopback Port와 별도 Compose Project를 사용하며
종료 시 Container와 Volume을 회수한다.

```bash
./monitoring/grafana/tests/run-e2e.sh
```

Prometheus·Alertmanager 검증까지 포함한 전체 Monitoring 검증은 다음 명령을 사용한다.

```bash
./monitoring/verify.sh --e2e
```
