# #416 DocGrid Grafana 운영 Dashboard 검증 결과

## 1. 검증 대상

| 항목 | 값 |
|---|---|
| Issue | `#416` |
| Branch | `feature/416` |
| 실행 Revision | `22e41b2` |
| 실행 일시 | 2026-10-04 02:56~03:01 KST |
| Host | macOS arm64, Docker Engine 29.4.1, Docker Compose 5.1.3 |
| Runtime | Grafana 13.2.3, Prometheus 3.5.5, Alertmanager 0.33.1, Python 3.14.6 |

검증 대상은 Grafana Container, Prometheus Data Source와 Dashboard File Provisioning, 운영 Dashboard,
정적 검증기, 실제 Prometheus 연동 E2E다. Java Production Code를 변경하지 않아 Backend Gradle Test는
실행하지 않았다.

## 2. 성공 기준

1. 번들 Grafana가 관리자 Credential 없이 Loopback 전용 읽기 화면으로 기동한다.
2. `docgrid-prometheus` Data Source와 `docgrid-operations` Dashboard가 UI 조작 없이 등록된다.
3. Dashboard는 7개 Row, 29개 시각 Panel, 3개 조회 변수를 제공한다.
4. Fixture Metric을 Prometheus가 실제 Scrape하고 Grafana Data Source Proxy가 조회한다.
5. Dashboard에 포함된 PromQL 32개가 실제 Prometheus에서 오류 없이 실행된다.
6. 기존 Prometheus Rule과 Alertmanager Routing 검증에 회귀가 없다.
7. 격리 E2E가 생성한 Container와 Volume을 종료 후 모두 회수한다.

## 3. Test Fixture

### 3.1 Backend Fixture

Backend Target은 다음 상태를 포함한 27개 Sample 계열을 1초마다 노출했다.

- Claim 가능 Embedding Job 4개
- 지연 Retry Job 2개
- 처리 중 Embedding Job 1개
- 활성 Worker 1개
- Circuit 보호 상태 1, Probe 실패 상태 1
- 처리 중 RAG 1개
- Claim 가능 Sync Outbox Event 2개
- 운영 Snapshot 나이 12초

### 3.2 Embedding Provider Fixture

Provider Target은 다음 상태를 포함한 13개 Sample 계열을 노출했다.

- Model Loaded 1
- 대기 요청 1개, Queue 상한 2개
- RSS 1 GiB, Memory Limit 4 GiB
- 성공 요청 10건, Overload 요청 1건
- 성공 요청 Histogram 4개 Bucket과 Count·Sum

Fixture는 운영 환경 측정치가 아니다. Dashboard의 Data Source, Label Matcher, PromQL 실행 경로를
반복 가능하게 검증하기 위한 결정적 입력이다.

## 4. 실행 결과

| Run | 목적 | 실행 방법 | 주요 결과 | 판정 |
|---|---|---|---|---|
| 01 | 최초 Grafana E2E | `./monitoring/grafana/tests/run-e2e.sh` | Compose Project 이름의 대문자 `T/Z`를 거부, Container 생성 전 종료 | 실패 후 수정 |
| 02 | Grafana 단독 E2E | `./monitoring/grafana/tests/run-e2e.sh` | Health OK, Data Source 1개, Dashboard 36개 Panel, Fixture 값 4, PromQL 32개 성공 | PASS |
| 03 | 전체 Monitoring 회귀 | `./monitoring/verify.sh --e2e` | Rule 20개, Rule Test 3개, Alertmanager 설정 9개, Routing E2E, Grafana E2E 성공 | PASS |
| 시각 검토 | 실제 Browser Rendering | 격리 Grafana에서 `docgrid-operations` 조회 | 세 변수와 7개 Row 표시, 상태 색상·Queue 시계열 렌더링 확인 | PASS |
| 정리 확인 | 격리 자원 누수 검사 | 이름 기준 Container·Volume 조회 | 일치하는 Container 0개, Volume 0개 | PASS |

### 4.1 최초 실패와 수정

첫 실행 ID는 `20261003T175627Z-62642`였다. Run ID를 그대로 Compose Project 이름에 사용하면서
대문자 `T`와 `Z`가 포함됐고, Docker Compose가 소문자 영문·숫자·하이픈·밑줄 규칙 위반으로
거부했다. Container와 Volume은 생성되지 않았다.

Run ID 형식을 `20261003t175700z-63206`처럼 소문자로 바꾼 뒤 같은 E2E를 다시 실행했다. 이 수정은
Metric이나 Dashboard 동작을 바꾸지 않고 실행 격리 이름만 유효하게 만든다.

### 4.2 Grafana E2E

Run 02는 최초 Image Pull을 포함해 2026-10-04 02:56:51~02:57:28 KST에 실행됐다.

```text
[성공] Grafana Health: database=ok
[성공] Data Source Provisioning: uid=docgrid-prometheus
[성공] Dashboard Provisioning: uid=docgrid-operations, panels=36
[성공] Grafana→Prometheus 실제 조회: claimable_jobs=4
[성공] Dashboard PromQL 실행: 32개
[완료] result=SUCCESS
```

36개 Panel에는 구분용 Row 7개와 실제 시각 Panel 29개가 포함된다. `claimable_jobs=4`는 정적 JSON을
읽은 값이 아니라 다음 실제 경로로 조회한 값이다.

```text
backend-metrics.txt
  → Python HTTP Metric Fixture
  → Prometheus 1초 Scrape
  → Grafana Provisioned Data Source
  → Grafana Data Source Proxy
  → Prometheus Query API
  → claimable_jobs=4 확인
```

그다음 Dashboard JSON의 Target 32개를 순회하고 `cluster=docgrid-test`, `environment=test`,
`instance=.*`, `$__rate_interval=1m`으로 치환해 전부 실행했다. 이 검사는 JSON 파싱만으로 찾을 수 없는
PromQL 문법 오류와 Label Matcher 오류를 검출한다.

### 4.3 전체 Monitoring 회귀

Run 03은 2026-10-04 03:00:25~03:01:00 KST에 실행됐다.

- Prometheus 설정 1개와 Rule 파일 3개 파싱 성공
- Backend 3개, 비동기 Pipeline 10개, Provider 7개로 총 20개 Rule 검증 성공
- Prometheus Rule Test 파일 3개 성공
- 기본·Test·Drill·채널 예시를 합쳐 Alertmanager 설정 9개 검증 성공
- Alertmanager Routing E2E: Firing 전달 2초, Resolved 전달 1초
- Grafana 정적 검증: Panel 36개, PromQL 32개
- Grafana E2E 재실행 성공
- 최종 출력 `Monitoring configuration validation: SUCCESS`

## 5. 시각 검토

Grafana 13.2.3 Browser 화면에서 다음을 직접 확인했다.

- `Cluster`, `Environment`, `Instance`가 모두 `All` 기본값으로 표시됨
- Backend와 Provider가 `UP`, 발생 중 경보가 `0`, Snapshot 나이가 `12s`로 표시됨
- Claim 가능 Job 4개와 지연 Retry Job 2개는 노란색, 처리 중 Job·활성 Worker는 정상 색상으로 표시됨
- 수동 Refresh 후 Gauge 시계열이 실제 값으로 렌더링됨
- 초기 Fixture Scrape 전 `No data`가 남지 않도록 Dashboard Refresh 선택 목록에 `15s`를 명시함

Browser Screenshot은 Codex 화면에서 확인했지만 파일 저장에는 성공하지 않아 Repository 증거로
남기지 않았다. 시각 판정은 보조 검증이며, 재현 가능한 근거는 API E2E Log와 32개 PromQL 실행
결과다.

## 6. 보안과 정리 결과

- 번들 Grafana Port는 `127.0.0.1`에만 Bind된다.
- 초기 관리자 계정을 생성하지 않고 익명 Viewer만 허용한다.
- Provisioning Data Source와 Dashboard는 UI에서 수정할 수 없다.
- Test Log와 Grafana 파일에서 Password·Token·Authorization Header·Access Key를 검색했고 결과는 0건이었다.
- Run 02·03과 시각 검토가 생성한 격리 Container·Network·Volume을 모두 제거했다.

공유 서버 또는 외부 Network에서는 조직 Grafana의 인증·TLS·권한 설정을 사용해야 한다. 번들 설정은
로컬 Loopback 운영 화면의 편의 구성을 검증한 결과다.

## 7. 증거 파일

- `docs/test-results/evidence/issue-416/run-01-compose-project-name-failure.log`
- `docs/test-results/evidence/issue-416/run-02-grafana-e2e.log`
- `docs/test-results/evidence/issue-416/run-03-monitoring-validation.log`

각 실행 목적과 반복 실행의 Raw Output을 별도 파일로 보존했다. Run 01은 Live File Capture 이전의
도구 출력을 실행 직후 그대로 옮긴 기록이며, Run 02·03은 `tee`로 실행 중 수집한 Log다.
