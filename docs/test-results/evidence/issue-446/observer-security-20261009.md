# 관측 VM 구성·접근 경계 — 2026-10-09

- 실행 ID: `ha446observer20261009a`
- 최종 확인: 2026-10-09 04:38 KST
- 목적: 앱/DB 장애와 분리된 관측 경로를 만들되 관리 포트를 외부 인터넷에 공개하지 않는다.
- 실행 위치: GCP API, 신규 관측 VM, 부하 VM, 앱 A VM.
- 성공 기준: 관측 VM RUNNING, Prometheus/Grafana 실행, 수집 대상 5/5 UP, k6만 9090 접근, Grafana 인증·loopback, 외부 포트 차단.

| 작업·명령/방법 | 결과 요약 | 해석 |
| --- | --- | --- |
| `gcloud compute firewall-rules create` — 관측 태그 DENY ingress 우선순위 800, IAP SSH·부하 VM 9090 ALLOW 우선순위 700 | 규칙 **3개 생성** | 외부 IP는 패키지 설치 outbound용이다. 우선순위가 높은 허용 경로 외의 신규 유입을 거부한다. |
| `gcloud compute instances create` — Debian 12, `e2-standard-2`, 균형 디스크 30GB, 서비스 계정 없음 | 관측 VM **1대 RUNNING**, 부착 디스크 **1개**, 서비스 계정 **0개** | VM 실행·디스크 보관에 추가 과금된다. 클러스터 장애 대상과 별도 VM이다. |
| `gcloud compute firewall-rules create` — 관측 VM `/32`에서 앱 8081·DB 8008, 부하 VM `/32`에서 관측 9090 | 세 규칙 **3/3 생성** | Patroni의 관리 API도 같은 8008에 있으므로 출처를 관측 VM 단일 내부 IP로만 제한했다. |
| 게스트 `apt-get install docker.io`, 공식 이미지 `prom/prometheus:v3.5.5`, `grafana/grafana:13.2.3` | Docker 설치 **성공**, 컨테이너 **2/2 running** | Prometheus retention **7일·15GB**, scrape 간격 **5초**, timeout **2초**. |
| 관측 VM Prometheus `query=up` | 앱 **2/2 UP**, Patroni **3/3 UP** | 각 metrics endpoint에서 실제 수집됨. `UP=1`만으로 DB의 쓰기 정합성은 증명하지 않는다. |
| Prometheus `patroni_primary`, `patroni_postgres_running` | `patroni_primary`: **1값 1개·0값 2개**; `patroni_postgres_running`: **1값 3개** | 단순 포트 열림이 아니라 역할·실행 메트릭도 수집됨. |
| Grafana `/api/datasources/uid/ha-prometheus` | 인증 없이 **401**, 보호된 관리자 인증 후 **200**, Prometheus 데이터소스 확인 | Grafana 3000은 **127.0.0.1에만** 바인딩. 관리자 암호는 관측 VM의 읽기 제한 파일에만 있고 문서·로그로 전송하지 않았다. |
| 부하 VM → 관측 VM `GET /-/ready`; 앱 A → 같은 주소 | 부하 VM **HTTP 200**, 앱 A **연결 불가** | 허용/비허용 원본 VM에서 경계를 각각 시험했다. |
| 로컬 외부 위치 → 관측 VM 공인 IP TCP 22·3000·9090 | 각 포트 **연결 불가 (3/3)** | IAP SSH는 별도 경로로 성공했다. 공인 IP가 있어도 관리 포트는 외부에서 열리지 않았다. |

기존 앱 8081 규칙은 GCP 내부 LB health check에 사용되며 출처 CIDR 두 개가 기존대로 남아 있다. 이를 관측 VM `/32` 하나로 바꾸면 LB 2/2 상태를 깨므로 **관측 VM과 LB health checker만 예외로 허용**한다. 프로젝트에는 정지된 과거 VM 태그를 대상으로 한 공인 3000 허용 규칙도 존재한다. 현재 실행 VM에는 적용되지 않았으며 이번 범위에서 타인의 과거 규칙을 변경하지 않았다. 향후 해당 VM을 재시작할 때 별도 감사가 필요하다.

관측 VM을 계속 RUNNING으로 두면 VM·디스크 비용이 지속된다. 관측이 끝나면 VM 중지 여부를 사용자가 결정해야 하며, 중지 후에도 디스크 비용은 남는다. 이번 작업에서는 VM·컨테이너를 내리지 않았다.
