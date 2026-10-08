# OpenSQL HA 실시간 관측 경로 구축과 k6 최종 카운터 대조 — 2026-10-09

관련: [이슈 #446](https://github.com/DocGrid/docgrid/issues/446). 코드 기준은 `develop`의 `6e27543`에서 분기한 `test/446`이다.

**결론:** 별도 관측 VM에서 Prometheus·Grafana가 실행되고 앱 A/B·Patroni 3대 **5/5 target**이 수집된다. 부하 VM의 k6 Remote Write **합성 1건**을 수신해 안전한 클라이언트 원장과 최종 카운터가 일치했다. 이는 실제 장애·HTTP 쓰기·RPO 검증 결과가 아니다.

```text
그림 1 — 현재 연결된 수집 경로

GCP 내부 부하 VM ── k6 결과별 Counter ── Remote Write :9090 ──┐
      │      └─ JSONL 안전 이벤트 2건은 부하 VM에 별도 보존       │
      │                                                       ▼
앱 A ─ Actuator :8081 ─┐                             관측 VM Prometheus
앱 B ─ Actuator :8081 ─┤                               5초 scrape / 2초 timeout
DB node1 ─ metrics-only :18008 ─┤                    7일·15GB 보존 상한
DB node2 ─ metrics-only :18008 ─┤                             │
DB node3 ─ metrics-only :18008 ─┘                             ▼
                                                   Grafana :3000 (loopback)
                                                   관리자 로그인 + IAP SSH 터널

각 DB의 게이트웨이: GET /metrics만 로컬 Patroni :8008로 전달
                   관리 경로·변경 메서드는 전달하지 않음

OpenProxy A/B는 현재 프로세스·6432 리스너만 재확인했다.
OpenProxy 자체의 시계열 패널은 이 단계에서 아직 연결하지 않았다.
```

앱 지표 2개와 Patroni 지표 3개는 실제 Prometheus의 **active target 5/5**에서 모두 `up`이었다. Patroni의 `patroni_primary` 값은 1인 노드 하나와 0인 노드 둘, `patroni_postgres_running`은 3/3이었다. 기존 8008 target의 오래된 시계열이 조회 시점에 잠시 함께 남아 있었으므로 단순 `up` 결과 개수 대신 active target 포트를 확인했다. 상세 원본 명령·숫자는 [현재 상태 로그](evidence/issue-446/current-state-20261009.md), [관측 VM·접근 경계 로그](evidence/issue-446/observer-security-20261009.md), [지표 전용 경계 보강 로그](evidence/issue-446/gateway-hardening-20261009.md)에 목적별로 나눴다.

```text
그림 2 — k6의 두 출력과 각자 주장할 수 있는 범위

수정된 ha_probe_load.js
  ├─ 요청 종료 상태 분류
  │     201 / 500 / 503 / 기타 HTTP 실패 / 결과 불명
  ├─ 기존 JSONL 출력 ──▶ 1초 계측·요청별 외부 원장
  │                       └─ run_id 외의 임의 지표 태그를 거부
  └─ Prometheus Remote Write ──▶ Grafana 시간축
                                └─ run_id 라벨은 이 출력에만 추가

시험 종료 후 verify_ha_prometheus_rw.py
  외부 원장의 종료 이벤트 수 ── 비교 ── Prometheus 마지막 Counter
              일치: 관측 전송의 최종 합계만 확인
              불일치: 원격 지표 검증 실패로 별도 표시

DB request_id 행 대조는 기존 도구의 별도 판정이다.
Prometheus 합계가 맞아도 DB의 누락·중복이 0이라고 주장하지 않는다.
```

| 변경 파일·구성 | 목적 |
| --- | --- |
| `scripts/opensql/ha_probe_load.js` | 결과별 태그 없는 Counter 5종을 추가해 502/504 같은 기타 HTTP 오류도 누락하지 않는다. |
| `scripts/opensql/run_ha_probe_k6.sh` | `HA_PROM_RW_URL`이 있을 때만 JSONL과 Remote Write를 병행하고, `run_id` 원격 라벨·stale marker·최종 카운터 대조를 적용한다. 미설정이면 기존 JSONL 전용 실행을 유지한다. |
| `scripts/opensql/verify_ha_prometheus_rw.py` | 사설 IPv4 수신 주소만 허용하고, 안전 원장의 완료 상태와 Prometheus 최종 Counter를 실행 ID별로 비교한다. 내부 주소·원시 HTTP 오류는 출력하지 않는다. |
| `scripts/opensql/test_verify_ha_prometheus_rw.py`, `scripts/opensql/test_ha_load_telemetry.py` | 불완전·중복 원장, 기타 오류, 태그 금지, 최종 합계의 한계를 회귀 시험한다. |
| `scripts/opensql/patroni_metrics_gateway.py`, `scripts/opensql/patroni_metrics_gateway.service` | DB 호스트의 비권한 프로세스가 정확한 `GET /metrics`만 로컬 Patroni로 전달한다. 기존 Patroni·PostgreSQL 프로세스는 재시작하지 않는다. |
| `scripts/opensql/test_patroni_metrics_gateway.py` | 관리 경로·변경 메서드 거부, 고정 upstream, upstream 실패, RFC1918 바인딩의 회귀 시험 5건. |
| GCP 관측 VM (저장소 비공개 운영 설정) | Prometheus 3.5.5, Grafana 13.2.3, 30GB 디스크, IAP 관리 경로. 실제 내부 대상 주소·Grafana 비밀은 Git에 넣지 않는다. |

```text
그림 3 — 관리 포트 접근 경계

외부 인터넷 ──X──▶ 관측 VM :22/:3000/:9090
                  우선순위 800 DENY ingress
                    ▲                  ▲
                    │ 우선순위 700     │ 우선순위 700
              IAP SSH 출처       부하 VM 내부 IP /32 → :9090
                    │
                    └─ SSH 터널 → 관측 VM loopback :3000 (Grafana 로그인)

관측 VM 내부 IP /32 ──▶ 앱 A/B :8081 (metrics)
관측 VM 내부 IP /32 ──▶ DB 3대 :18008 (metrics-only)
관측 VM ──X──▶ DB 3대 :8008 (Patroni 관리 REST)
부하 VM ──X──▶ 앱 :8081 / DB :18008
GCP LB health checker ──▶ 앱 A/B :8081 (기존 예외 유지)

앱 A에서 관측 VM :9090 연결: 실패 관측
부하 VM에서 관측 VM :9090 readiness: HTTP 200 관측
외부에서 관측 VM :22/:3000/:9090 연결: 3/3 실패 관측
```

설치된 DB 컨테이너 안의 실행 Patroni 설정을 확인한 결과 REST API 인증·TLS 항목이 **세 노드 모두 없었다**. 초기에는 관측 VM에서 8008로 직접 수집했고, 이를 위험한 임시 경계로 판정했다. DB 호스트의 지표 전용 게이트웨이를 검증·배포한 뒤 수집을 18008로 전환했다. 기본 VPC 내부 허용 규칙 때문에 초기 8008 허용 규칙만 삭제해도 관측 VM에서 관리 포트에 계속 접속할 수 있었으므로, 관측 VM 출처의 8008을 명시적으로 거부했다. 현재 관측 VM→8008은 3/3 연결 불가이고 18008 지표는 3/3 HTTP 200이다. 다른 기존 VPC 내부 VM의 8008 접근 정책 전체를 바꾼 것은 아니므로, 이는 클러스터 전반의 Patroni 관리 API 보안 완료를 뜻하지 않는다. 관측 VM에는 GCP 서비스 계정을 붙이지 않았다. 순서와 실패·수정 증거는 [지표 전용 경계 보강 로그](evidence/issue-446/gateway-hardening-20261009.md)에 있다.

| 목적·실행 위치 | 실행 명령·방법 | 숫자 결과 | 판정 |
| --- | --- | --- | --- |
| 현재 클러스터 · GCP/VM 읽기 | Compute 목록·LB health, 앱 JAR 해시, Patroni REST, etcd 로컬 health·멤버 목록, 프록시 프로세스·리스너 | 앱 **2/2**, DB **3/3**, LB **2/2**, leader **1**·replica **2**, etcd health·멤버 **3/3**, 프록시 **2/2** | 수집 시점 기준 상태 확인. [로그](evidence/issue-446/current-state-20261009.md) |
| 코드 회귀 · 로컬/부하 VM | Python 기존 세 suite + 게이트웨이 suite, `bash -n`, `node --check`, k6 2.3.0 `inspect -e ...` | Python **22/22**, Bash·JS·k6 inspect 최종 **각 통과**. 초기 잘못된 unittest 탐색 2회·inspect 환경값 누락 1회 실패 보존 | 코드·런타임 파싱 확인. [기존 로그](evidence/issue-446/local-regression-20261009.md), [게이트웨이 로그](evidence/issue-446/gateway-hardening-20261009.md) |
| 관측 인프라 · GCP | Docker 컨테이너·Prometheus active targets·Patroni 지표·Grafana API·내외부 연결 | 컨테이너 **2/2**, active targets **5/5**, primary metric **1/3**, Grafana 무인증 **401**·인증 **200** | 수집·접근 경계 통과. [초기 로그](evidence/issue-446/observer-security-20261009.md), [보강 로그](evidence/issue-446/gateway-hardening-20261009.md) |
| k6 전송 · GCP 내부 부하 VM | `--out json` + `--out experimental-prometheus-rw`, 원장/Counter 대조 | 실행 `ha446rw20261009a`, 안전 이벤트 **2건**, 201 예상·관측 **1/1**, 태그 붙은 JSONL Point **0/5** | **합성 전송 경로 통과**. [로그](evidence/issue-446/remote-write-ha446rw20261009a.md) |

```text
그림 4 — 아직 검증하지 않은 장면

이번 완료:
  앱·Patroni 지표 수집 → Grafana datasource 연결 → 합성 k6 Counter 대조

다음 장애 시연 전 필요:
  새 유효 시험 JWT 준비 → 실제 HA probe 소량 기준선
       │
       ▼
  장애 주입 → 요청별 JSONL 원장 → 최종 primary의 request_id 대조
       │
       ├─ Grafana: 시간대별 실패·복구를 설명하는 보조 화면
       └─ 원장/DB: 201 누락·중복·결과 불명의 최종 근거

미실행: 이번 변경으로 실제 primary/OpenProxy를 중단하거나
        실제 HA probe HTTP 쓰기를 재시험하지 않았다.
```

현재 부하 VM에는 재사용 가능한 **만료 전·0600 시험 JWT가 0개**라 실제 앱 쓰기 부하까지 확대하지 않았다. 또한 Remote Write의 마지막 카운터가 일치해도 중간 전송 공백은 증명하지 않는다. k6 Trend의 누적 p95/p99를 ‘최근 5초 지연’처럼 그리지 않으며, OpenProxy·etcd 자체 시계열은 아직 수집하지 않는다. 안전한 이벤트·지표 원본은 부하 VM의 실행 ID 전용 폴더에 보존했고, 비식별 숫자만 이 문서에 남겼다.

관측 VM은 계속 실행 중이다. 추가 비용은 VM 가동 시간, 30GB 디스크, 사용 중 외부 IPv4와 가능한 네트워크 사용량에 발생한다. 중지하면 VM 계산 자원 사용료는 멈추지만 디스크 비용은 남는다. 정확한 청구액은 [Google Cloud 요금표](https://cloud.google.com/products/compute/pricing)와 청구 계정에서 확인해야 한다. 이 작업에서는 앱·DB·부하 VM과 관측 VM을 중지하거나 장애 주입하지 않았다.
