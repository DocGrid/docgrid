# 동기 standby 연결 장애에서 HTTP 500·503이 발생한 계층 진단

2026-10-04 시험용 GCP 3노드에서 [기존 동기 standby 연결 장애 시험](opensql-sync-standby-link-fault-20261004.md)의 HTTP 500/503을 재현하고 발생 계층을 분리했다. 이번 **30 req/s × 120초 진단 실행**의 500 **372건은 백엔드 응답**, 503 **1,579건은 LB가 건강한 백엔드를 고르지 못해 반환**한 것으로 3,539개 합성 요청 ID와 Cloud Logging이 1:1 일치했다. 앱 A/B는 각 5개 Hikari 연결이 모두 사용된 동안 대기가 최대 97/72개, 연결 획득 시간 초과가 253/198회 늘었다. LB의 0/2 healthy 기간도 관측했다. 따라서 503을 OpenProxy 또는 DB가 직접 반환한 것으로 설명하면 틀린다.

이는 [이슈 #435](https://github.com/DocGrid/docgrid/issues/435)의 진단 결과이며 **프로덕션 코드는 변경하지 않았다**. 동기 커밋 대기·DB 연결 풀 포화·DB 포함 readiness 실패가 이어졌다는 해석은 여러 독립 계측의 시간 일치와 설정에서 나온 **강한 추론**이다. 단, 배포된 앱 JAR에는 요청 ID별 진단 코드가 없어서 500 각 건의 Java 예외를 확정하지 못했다. 372개 중 **371개**의 LB 지연이 4~6초로 앱의 5초 Hikari 연결 시간 초과와 일치하고, **1개는 55.368ms**여서 별도 즉시 실패로 남긴다.

## 무엇을 분리해 측정했나

| 계층 | 실행 당시 설정·수집 | 실제 관측 |
| --- | --- | --- |
| 부하·외부 원장 | GCP 내부 k6, HTTP 요청에 합성 `ha_request_id`, 이벤트 원장을 DB 밖에 기록 | 동기 정상 기준선 600/600 201·미전송 0; 장애 실행 3,539건 전송·62건 미전송 |
| 앱 A/B | 앱별 JVM 소켓·Hikari Prometheus 지표 1초 표본, 150개씩 | 활성 최대 각 5/5, 대기 최대 A 97/B 72, 연결 시간 초과 증가 A 253/B 198 |
| OpenProxy·OpenSQL | 두 프록시와 3 DB 노드는 그대로; primary에서 현재 sync standby 복제 TCP 한 링크만 차단 | 주입 직전 leader 1·streaming 2·sync 1/async 1; 규칙에 패킷 적중, 이후 정상 복제 복귀 |
| LB 건강 검사 | `/actuator/health/readiness` 5초 간격, timeout 3초, unhealthy threshold 2; 앱 readiness에 `db` 포함 | 50개 건강 표본 중 0/2 healthy 18개, 최초 11:48:43.623·최후 11:49:34.789 UTC |
| LB 요청 로그 | 시험 중에만 sampleRate 1.0 사용, 호스트·IP·계정은 기록 전에 제거 | 3,539/3,539 요청 ID·HTTP 결과 일치; 500은 `response_sent_by_backend`, 503은 `failed_to_pick_backend` |
| DB 최종 상태 | synthetic `request_id`를 primary의 `ha_probe_writes`와 대조 | 201 1,578개 각각 1행; 500/503 1,951개 0행; 결과 불명 10개 중 6개 반영 |

### 그림 1. 실제 장애·관측 경계

```text

GCP 내부 k6 ──합성 request_id, JWT, POST──▶ 내부 LB
    │                                       │
    │ 외부 원장: sent/201/500/503/unknown    ├─ 앱 A ─ Hikari 5 ┐
    │                                       └─ 앱 B ─ Hikari 5 ┤
    │                                                         ▼
    │                                                OpenProxy A/B
    │                                                         │
    │                                                         ▼
    └──── DB 밖 원장 대조 ◀──── OpenSQL primary ─ WAL ─▶ sync standby
                                          ▲                 │
                                          └── 선택된 복제 TCP만 REJECT

VM·Patroni REST·etcd·OpenProxy 포트는 끊지 않았다.
```

이 그림의 요청 ID는 합성 문자열이고 서비스 사용자 정보가 아니다. 장애는 DB 복제 링크 한 곳에만 넣었다. 기존 [#434](https://github.com/DocGrid/docgrid/pull/434)의 장애 유형과 같지만 이번에는 LB/앱 계층 계측을 추가했다.

## 시간축과 500·503의 차이

### 그림 2. 장애 직후 요청이 겪은 두 갈래

```text

11:48:28.596  선택된 sync standby 복제 패킷 REJECT
      │
      ├─ LB가 앱을 고름 ─▶ 앱 트랜잭션 시작
      │                     ├─ DB 연결·커밋 대기 (계측과 설정에서 추론)
      │                     ├─ Hikari 5/5 사용, 대기 증가
      │                     └─ 연결 획득 실패 → 앱이 HTTP 500 응답
      │                          LB 기록: response_sent_by_backend
      │
      └─ health check가 두 앱 모두 UNHEALTHY로 판단
                           (DB 포함 readiness가 실패했다는 경로는 추론)
                            └─ 보낼 백엔드 없음 → LB가 HTTP 503 응답
                                 LB 기록: failed_to_pick_backend

11:49:35.645  연속 201 사이 67.060초 공백 뒤 201 재관측
11:49:57.339  독립 타이머가 차단 규칙 자동 제거
```

첫 500은 주입 **0.066초** 후, 첫 503은 **13.977초** 후였다. LB 건강 표본은 11:48:43.623~11:49:34.789 UTC에서 0/2를 기록했다. 500의 LB 지연은 **371/372개가 4~6초**, 나머지 **1개가 55.368ms**다. 따라서 대부분의 500은 Hikari의 5초 연결 획득 시간 초과와 일치하지만, 1개까지 같은 원인이라고 단정할 수 없다. 앱 journal에서도 `Connection is not available` 문자열이 A **429**·B **394**회 기록됐으나 이는 **로그 행 수이지 실패 요청 수가 아니다**.

### 그림 3. 같은 5xx여도 반환 주체는 다르다

```text

HTTP 500 × 372 ─ LB proxyStatus=response_sent_by_backend
                └─ 앱이 응답; 371건은 4~6초, 1건은 55.368ms

HTTP 503 × 1,579 ─ LB proxyStatus=failed_to_pick_backend
                  └─ 건강한 앱을 선택하지 못함; LB 지연 p50 0.106ms

HTTP 상태 없음 × 10 ─ client_disconnected_before_any_response
                  └─ k6 10초 timeout; 성공/실패로 재분류하지 않음
```

## 요청 단위 결과와 한계

| 실행 | 전송·미전송 | HTTP 201 / 500 / 503 / 결과 불명 | 전체 p95 / p99 | DB 최종 대조 |
| --- | --- | --- | --- | --- |
| [동기 정상 `ha435syncbase`](evidence/issue-435/ha435syncbase/실행-결과.md) | 600·0 | 600 / 0 / 0 / 0 | 54.54 / 56.75ms | 201 누락·중복 0 |
| [링크 장애 `ha435fault01`](evidence/issue-435/ha435fault01/실행-결과.md) | 3,539·62 | 1,578 / 372 / 1,579 / 10 | 5,011.75 / 5,022.60ms | 201 누락·중복 0, 결과 불명 반영 6 |

### 그림 4. HTTP 결과와 DB의 서로 다른 판정

```text

DB 밖 HTTP 원장 3,539개          최종 primary의 request_id 행
   201 × 1,578 ───────────────▶ 각각 1행: 관측 누락 0·중복 0
   500/503 × 1,951 ───────────▶ 각각 0행: 실패 반영 0
   결과 불명 × 10 ─────────────▶ 6개 1행 / 4개 0행
   미전송 × 62 ────────────────▶ HTTP 요청 자체가 없으므로 DB 대조 대상 아님

재시도 정책을 추가한다면 결과 불명 6개 때문에 request_id 멱등성이 먼저 필요하다.
```

`ha_evidence.py verify`로 외부 원장 재생을 검증했다. 장애 실행의 `normal_baseline_pass=false`는 정상 기준선용 판정식이 장애 중 실패와 미전송을 불허하기 때문이지, **201 데이터가 누락됐다는 뜻은 아니다**. 반대로 201 누락 0은 이 시험의 관측값이지 동기 복제의 모든 장애에서 절대 RPO 0이라는 보장이 아니다. 이번 실행은 초기 VU 80에서 급증한 대기 때문에 미전송 62건이 발생했으므로 #434의 미전송 0 실행과 **처리량·실패율을 정밀 비교하지 않는다**. 전체 3,539개에 대한 LB 요청 ID와 응답은 일치한다.

## 실행 절차·명령·복구

| 위치 | 명령·절차 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| DB·LB 관리면 | `patronictl list/show-config`, `pg_stat_replication`, `etcdctl member list`, `get-health`, READY snapshot 조회 | leader 1/replica 2, etcd 멤버 3, LB 2/2, 기존 설정 SHA-256 저장 | 장애 전 관문. 격리 etcd restore는 이 실행에서 다시 하지 않았고 이전 시험의 결과를 재사용 |
| DB primary | 독립 15분 `synchronous_mode=null` 원복 타이머 → `patronictl edit-config -q --set synchronous_mode=true --force` → sync 1/async 1 확인 | 동기 모드 진입, 정상 기준선 600/600 201 | 설정 변경 자체의 영향과 장애를 분리 |
| GCP 내부 부하 VM | `HA_TRACE_QUERY=1 run_ha_probe_k6.sh ... 30 20s ... 80` | 600/600 201, 미전송 0 | 정상 관문 통과 |
| 앱 A/B·관리면 | `sample_ha_app_connections.py` 각 1초·150개; `sample_lb_health.py` 3초 목표·150초 | 앱 표본 각 150개, LB 표본 50개, 계측 결측 0 | 원본 주소는 내보내지 않고 숫자만 실시간 기록 |
| DB primary | `standby_link_fault_guard.sh arm ... 120` 확인 후 `inject`; systemd가 `recover` | 11:48:28.596 차단 → 11:49:57.339 자동 제거 | 차단된 실제 시간 약 88.7초. 규칙은 특정 복제 TCP만 겨냥 |
| GCP 내부 부하 VM | `HA_TRACE_QUERY=1 run_ha_probe_k6.sh ... 30 120s ... 80` | 전송 3,539·미전송 62, 실행 코드 99 | 실패 응답과 미전송을 숨기지 않음 |
| 관리면·로컬 오프라인 | `export_lb_request_outcomes.py`로 LB 로그를 메모리에서 비식별화; DB `COPY` → `import_completed_ha_k6.py` → `ha_evidence.py verify` | LB 3,539/3,539 완전 대조, 201 누락·중복 0, 불명 반영 6 | HTTP 응답·LB 반환 주체·DB 최종 상태를 분리 |
| DB·LB 관리면 | 정확한 규칙 제거 확인 → `synchronous_mode=null` → 설정 SHA-256·Patroni·LB 재조회 | 원래 SHA-256 `e1fadf809fbad93e696c678920fe6175e4b131e555e1c372f8d70d9535dbb5ee`, leader 1/streaming 2, LB 2/2 | 클러스터 정상화. 합성 계정 0, 임시 SSH 키 0, LB 요청 로깅 비활성 확인 |

## 수정 판단

현재 증거로는 **안전한 자동 쓰기 재시도나 Hikari 풀 확대를 `[Fix]`로 넣지 않는다**. 동기 커밋이 막힌 동안 풀을 키워도 DB의 확인 응답이 빨라지는 것은 아니고, 요청 대기·부하만 늘 수 있다. readiness에서 DB를 제거해 503을 500으로 바꾸는 것도 쓰기 가용성을 복구한 것이 아니다. 무엇보다 결과 불명 10개 중 6개가 DB에 반영됐으므로 멱등성 없이 쓰기를 재시도하면 중복 위험이 있다.

후속 선택지는 (1) 동기 standby 장애 시 허용할 쓰기 중단 시간과 복제 정책을 먼저 결정하고, (2) 배포 JAR에 이미 저장소에 있는 합성 probe 진단 클래스를 포함한 뒤 55ms 즉시 500 한 건의 예외 단계와 OpenProxy/DB 경로를 요청 ID별로 확인하는 것이다. 이 확인 전에는 **모든 500의 세부 원인이 동일하다**고 주장하지 않는다. 앱 프로덕션 동작이나 인가·재시도 정책은 이번 PR에서 바꾸지 않았다.

공개 증거는 합성 ID·숫자·UTC 시각만 포함한다. Cloud Logging 원문과 앱 journal 원문은 내부 주소·오류 메시지를 포함할 수 있어 저장하지 않았다. 임시 JWT와 접근키는 삭제했고 시험용 VM은 후속 작업을 위해 실행 상태로 유지했다.
