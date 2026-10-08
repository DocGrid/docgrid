# Patroni 지표 경계 보강 — 2026-10-09

- 실행 ID: `ha446gateway20261009a`
- 확인 시각: 2026-10-09 약 04:45~05:00 KST. 각 명령의 정확한 초 단위 시각은 클라우드 감사 로그와 systemd journal에 남으며 이 비식별 요약에는 집계 구간만 기록한다.
- 목적: 관측 VM이 무인증 Patroni 관리 REST API에 접근하지 않으면서 DB 3대의 역할 지표를 계속 수집한다.
- 실행 위치: 로컬 독립 작업트리, GCP DB VM 3대, 관측 VM, 부하 VM, GCP 방화벽·LB API.
- 성공 기준: 게이트웨이 회귀 4/4, 각 DB의 `/metrics` 200·관리 GET/POST 거부, Prometheus 새 18008 active target 3/3, 관측 VM→8008 차단 3/3, 부하 VM→18008·앱 8081 차단, LB HEALTHY 2/2.
- 비밀·주소: 내부 IP와 프로젝트 ID, SSH 키, API 암호는 기록 전에 제외했다. SSH 개인키는 임시 디렉터리에서 실행 종료 시 삭제했고 공개키는 30분 만료로 등록했다.

## 발견과 수정 순서

| 순서·실행 위치 | 명령·방법 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| 1. DB 컨테이너 3대 | 실행 Patroni YAML의 `restapi` 항목을 컨테이너 내부에서 읽고 인증·TLS 키 존재 여부만 판정 | 노드 **3/3 모두 인증·TLS 항목 없음** | 직접 8008 scrape는 관측 VM에 무인증 관리 REST API의 네트워크 경로까지 제공한다. 비밀 원문은 출력하지 않았다. |
| 2. 로컬 첫 테스트 | `python3 -m unittest scripts/opensql/test_patroni_metrics_gateway.py -v` | **import 오류 1회** | 테스트 모듈 경로를 잘못 지정한 실행 오류. 코드는 아직 검증되지 않았다. |
| 3. 로컬 재실행 | `PYTHONPATH=scripts/opensql python3 -m unittest test_patroni_metrics_gateway -v` | **4/4 통과** | 고정 upstream `GET /metrics`만 전달, 관리 GET 404·변경 메서드 405, upstream 오류 502를 검증했다. 로컬 소켓 바인딩이 필요한 시험이다. |
| 4. DB node1 첫 설치 | `gcloud compute scp` → `sudo install` → `systemctl enable --now` | **기동 실패 1회**, `216/GROUP` | 해당 DB OS에는 `nogroup`이 없었다. 테스트 실패를 숨기지 않고 OS의 계정 구성을 재확인했다. |
| 5. DB node1 수정 | 그룹 확인 후 systemd unit을 `DynamicUser=yes`로 바꿔 재시작 | 서비스 active, `/metrics` **200** | 공용 `nobody` 계정 경고도 제거하고 전용 임시 사용자로 실행했다. 재시작 직후 첫 curl 한 번은 기동 경합으로 실패했으나 다음 조회는 200이었다. Patroni·DB는 재시작하지 않았다. |
| 6. DB node2/3 설치 | 동일 코드·unit 배포, private IP에만 바인딩, systemd active 확인 | **2/2 active**, `/metrics` **200 2/2**, `/patroni` **404 2/2**, `POST /switchover` **405 2/2** | 새 포트에서 지표와 관리 경로가 분리됐다. node1도 수정 전·후 지표 200과 관리 GET 404·POST 405를 개별 확인했다. |
| 7. GCP 방화벽 | 관측 VM `/32` → DB 18008 allow 규칙 생성 | 생성 **1건 성공** | 기본 내부 허용과 별개로 명시적인 수집 예외를 만들었다. |
| 8. 관측 VM | DB node1/2/3의 새 18008에 `GET /metrics`, `GET /patroni` | 지표 **200 3/3**, 관리 조회 **404 3/3** | 관측 VM에서 실제 네트워크 경로가 열렸다. |
| 9. 관측 VM | Prometheus 대상 8008→18008 3곳 변경, Prometheus 컨테이너만 재시작, `/api/v1/targets?state=active` 조회 | active target **5/5 up**, DB 포트 **18008 3/3**, 앱 포트 **8081 2/2** | 이전 8008 시계열은 TSDB 조회에서 잠시 함께 보였으므로 단순 `up` 시계열 수가 아니라 현재 active target으로 판정했다. `patroni_primary=1` 1개·`0` 2개, `patroni_postgres_running=1` 3개도 새 포트에서 확인했다. |
| 10. GCP 방화벽 첫 정리 | 우리가 만든 관측 VM→DB 8008 allow 규칙 삭제 후 관측 VM에서 8008 TCP 연결 확인 | 삭제 **성공**, 하지만 **8008 OPEN 3/3** | 기존 `default-allow-internal`이 모든 내부 TCP를 허용했다. 허용 규칙 삭제만으로는 접근 차단이 되지 않는다는 실패 사례다. |
| 11. GCP 방화벽 보강 | 관측 VM `/32`→DB 8008 명시 DENY(우선순위 650); DB 18008·앱 8081은 필요한 관측 VM/LB 예외보다 낮은 우선순위의 전체 출처 DENY(우선순위 1000) | DENY **3개 생성 성공** | 앱 8081의 기존 LB health checker ALLOW 우선순위 900을 유지했다. 다른 VM의 기존 8008 정책까지 수정하지 않았다. |
| 12. 양쪽 VM 재검증 | 관측 VM→DB 8008/18008·앱 8081, 부하 VM→DB 18008·앱 8081·관측 9090 | 관측 VM: 8008 **CLOSED 3/3**, 18008 **200 3/3**, 앱 8081 **200 2/2**. 부하 VM: DB·앱 지표 **CLOSED 2/2**, 9090 **OPEN 1/1** | 목적별 허용·거부를 양쪽 출처에서 검증했다. 18008의 관리 POST는 **405 3/3**(node1 초기, node2/3 설치, 관측 VM node1 재확인). |
| 13. GCP LB API | `gcloud compute backend-services get-health` | 앱 A/B **HEALTHY 2/2** | 8081 거부 규칙이 LB의 health check를 끊지 않았다. |
| 14. 로컬 최종 회귀 | `PYTHONPATH=scripts/opensql python3 -m unittest test_verify_ha_prometheus_rw test_ha_load_telemetry test_sanitize_ha_k6_events test_patroni_metrics_gateway`; `bash -n`; `node --check`; `git diff --check` | Python **21/21**, 나머지 **각 종료 0** | 게이트웨이와 기존 k6 원장 계측의 로컬 회귀를 함께 확인했다. |

## 경계와 남은 한계

```text
관측 VM ──▶ DB :18008 ── GET /metrics ──▶ 127.0.0.1:8008 Patroni
         │               └─ 최대 1MiB·2초 timeout, 실패 시 502
         └─X─▶ DB :8008 (명시적인 출처별 DENY)

부하 VM ──X─▶ DB :18008 / 앱 :8081
부하 VM ─────▶ 관측 VM :9090 (Remote Write)
LB health checker ─────▶ 앱 :8081 (기존 허용 유지)
```

이 결과는 **관측 VM에서 Patroni 관리 포트로 가는 경로**를 차단했다는 뜻이다. 기존 `default-allow-internal`은 여전히 다른 내부 VM에 넓은 포트를 허용할 수 있다. 이 PR은 OpenSQL 운영망 전체의 REST 인증·TLS·세그먼테이션을 정비하지 않는다. 게이트웨이 역시 DB 호스트에 새 systemd 서비스 3개를 남기며, Patroni 재기동 후 지표 응답과 systemd 자동 재시작은 이번에는 따로 장애 주입하지 않았다. Prometheus·Grafana VM과 부하·앱·DB VM은 모두 중지하지 않았다.
