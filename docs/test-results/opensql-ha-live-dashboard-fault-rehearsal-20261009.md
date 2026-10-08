# GCP OpenSQL HA 대시보드·실제 쓰기·장애 리허설 — 2026-10-09

- 이슈: [#448](https://github.com/DocGrid/docgrid/issues/448)
- 실행 코드 기준: `9ba3e515a53979fd7fe5ffe74681fe96792bb036` (#447 머지 직후). 이 문서와 HA Dashboard는 별도 `test/448` 변경이다.
- 환경: GCP 내부 부하 VM 1대 → 내부 LB → 백엔드 A/B 2대 → OpenProxy A/B 2개 → PostgreSQL/Patroni 3노드. 별도 관측 VM의 Prometheus·Grafana, 공용 캐시 VM 1대가 켜져 있었다. DB 노드 두 대에 OpenProxy가 공존하므로 VM 수와 논리 구성요소 수를 혼동하지 않는다.
- 목적: **같은 화면**에서 실제 HTTP 쓰기·앱·DB·k6 상태를 관찰하되, 데이터 정합성은 외부 요청 원장과 DB 행 대조로 독립 판정한다.
- 원본: 실행별 `k6-events.jsonl`, `k6-summary.json`, `원격-지표-대조.json`, `계측-1초.csv`, `실행-기록.txt`는 부하 VM의 실행 ID별 보호된 폴더에 보존했다. 원본 k6 지표에는 비공개 주소가 들어갈 수 있어 저장소에 복사하지 않았다. 각 실행 문서에 이벤트 원장의 SHA-256을 적었다.

## 관측 경로와 판정 경계

```text
GCP 부하 VM: k6 ──HTTP request_id──▶ 내부 LB ──▶ 앱 A/B
       │                                       │          │
       │                                  Hikari 풀       ▼
       │                                  대기 지표   OpenProxy A/B
       │                                                  │
       ├─ 완료 이벤트·지연 ──Remote Write──────────────────┐ │
       └─ DB 밖 원장(201/실패/불명)                         │ ▼
                                             PostgreSQL primary + replica 2
                                              │      Patroni 지표     │
                                              └──────────┬───────────┘
                                                         ▼
                                               Prometheus → Grafana

화면: 응답률·VU·앱/DB 수집·primary 역할·WAL 재생 대기
최종 판정: 원장의 request_id ↔ 복구 후 primary DB의 행 수
```

Grafana Dashboard UID는 `docgrid-opensql-ha-live`다. 패널 8개(제목 Row 2개 포함), `run_id` 선택 변수 1개이며 인증된 관측 VM에 실제 가져와 읽어 보았다. OpenProxy **프로세스 상태 자체는 Prometheus에 직접 수집하지 않는다.** 따라서 화면만으로 A/B 프록시가 살아 있다고 판단하지 않고 장애 가드의 프로세스·포트 확인과 HTTP 결과를 함께 사용한다. k6 Remote Write의 누적 p95/p99를 구간 복구 지연인 것처럼 표시하지 않도록 해당 패널을 제외했다.

## 실행 결과

| 실행 ID | 조건 | HTTP 결과 | 드롭 | p95 / p99 | 요청 ID별 DB 판정 |
| --- | --- | --- | ---: | --- | --- |
| [`ha448smoke01`](evidence/issue-448/ha448smoke01.md) | 1 req/s, 5초, 사전 연결 확인 | 201 **6/6** | 0 | 91.96 / 99.17 ms | 201 누락·중복 **0/0** |
| [`ha448base01`](evidence/issue-448/ha448base01.md) | 100 req/s, 60초, 정상 기준선 | 201 **6,001/6,001** | 0 | 52.20 / 54.39 ms | 201 누락·중복 **0/0** |
| [`ha448proxy01`](evidence/issue-448/ha448proxy01.md) | 100 req/s, 120초, OpenProxy A 프로세스 중단·복구 | 201 **11,994**, 500 **7** / 12,001 | 0 | 52.09 / 55.68 ms | 201 누락·중복 **0/0**, 500 DB 반영 **0** |
| [`ha448proc01`](evidence/issue-448/ha448proc01.md) | 30 req/s, 180초, primary postmaster SIGKILL | 201 **5,161**, 500 **230**, 401 **4**, 결과 불명 **5** / 5,400 | 0 | 2,715.64 / 5,011.01 ms | 201 누락·중복 **0/0**, 불명 중 DB 반영 **3** |

기준선 k6 종료 코드는 **0**, 프록시 실행도 **0**이다. primary 실행은 HTTP 실패율 임계값 때문에 **99**이며 장애 시험의 관측 결과를 숨기지 않는다. 네 실행 모두 VM 1초 계측과 Prometheus 최종 Counter 합계 대조가 통과했다. 이 최종 합계는 중간 Remote Write 전송 공백까지 없었다는 증명은 아니다.

```text
OpenProxy A 프로세스 장애 — UTC
20:24:23  k6 100 req/s 시작
20:25:03.936  A 프로세스 중단 (독립 복구 가드 무장)
20:25:04.114~.153  HTTP 500 총 7건
20:25:42.240  A 프로세스·포트 복구 확인
20:26:25  k6 종료, 요청 ID 12,001건 대조 완료

중단~복구: 38.304초(프록시 프로세스 기준)
연속된 201 응답 간 최대 간격: 0.049초(HTTP 관측 기준)
→ 프록시 하나가 내려가도 정상 쓰기가 계속됐지만, 오류 0건은 아니다.
```

```text
primary PostgreSQL 프로세스 장애 — UTC
20:34:00  k6 30 req/s 시작
20:34:19  현재 primary의 postmaster만 SIGKILL
20:34:19.990  첫 201 이외 응답
20:34:32.109  마지막 201 이외 응답
20:37:01  k6 종료

201 응답 사이 최대 간격: 11.360초
첫~마지막 비201 관측 구간: 12.119초
Patroni: 같은 노드가 다시 leader/running, replica 2대 streaming/lag 0
→ 새 리더 선출 시험이 아니라 같은 노드 DB 프로세스 재시작 시험이다.
```

## 정합성과 운영 해석

```text
클라이언트 응답         복구 후 primary 행        판정
201  ───────────────▶ 1행                    보존 관측
201  ───────────────▶ 0행 / 2행 이상          누락 / 중복
500·401 ────────────▶ 0행                    이 실행에서는 미반영
결과 불명 5건 ──────▶ 3행(각 ID 1행)          응답 없음 ≠ DB 미반영

primary 실행의 전체 DB 고유 ID: 5,164개 = 201 ID 5,161개 + 불명 반영 ID 3개
```

이번 실행에서 201 누락이 0건인 것은 **관측값**이며 비동기 복제의 절대 RPO 0 보장이 아니다. 500·401·불명은 사용자가 겪은 가용성 실패다. 401 4건의 개별 원인과 500 발생 계층은 이 실행에서 확정하지 않았다. 결과 불명 3건이 실제 반영됐으므로 멱등 계약 없이 무조건 재시도하면 중복 위험이 있다. 장애 전후 HTTP 최대 성공 간격과 프로세스 재기동 시간을 하나의 RTO 수치로 섞지 않았다.

## 원복 및 미검증 범위

- 장애 뒤 LB 백엔드 **2/2 HEALTHY**, Patroni **leader 1·streaming replica 2(lag 0)**, OpenProxy **A/B 모두 프로세스 1·포트 ready**를 다시 확인했다.
- 합성 ADMIN 계정 **4개 삭제**, DB VM 3대의 fixture 타이머 **3/3 해제**, 프록시 복구 가드 **해제**, 임시 JWT **2개 삭제**, 원격 임시 장애·대조 스크립트 삭제를 확인했다.
- 앱·부하·관측·DB VM은 시연 재사용을 위해 **계속 RUNNING**이다. VM·디스크·외부 IPv4 비용은 계속 발생한다.
- OpenProxy B 장애, 리더 VM 상실, 다중 반복 분포, 프록시별 실시간 Prometheus status, 비동기 복제 지연 중 RPO 경계는 이번 실행 범위 밖이다.
- 원본 측정값의 해시와 명령·실패 재시도는 [실행별 로그](evidence/issue-448/dashboard-and-preflight.md) 및 각 실행 문서를 본다.
