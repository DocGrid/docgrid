# GCP 부하 중 OpenProxy A/B 지속 장애 전환 계측

관련: [이슈 #391](https://github.com/DocGrid/docgrid/issues/391), [정상 부하 하네스 PR #390](https://github.com/DocGrid/docgrid/pull/390). **결론은 A 경로 통과, B 경로는 500 10건과 ID별 DB 대조 미완료로 최종 판정 보류**다. 100 req/s에서 A 중단 중 시작한 13,001건은 모두 201로 끝났고, B 중단 중 13,001건에서는 12,991건 201·10건 500이었다. “프록시 2대이므로 무중단”이라는 선언 대신 실패와 증거 공백을 함께 남긴다.

## 왜 이 시험을 했나

PR #390은 실제 앱 A/B → 내부 LB → OpenProxy → OpenSQL 경로에서 정상 쓰기 기준선과 DB 대조 원장을 만들었다. 하지만 프록시 한 대가 죽었을 때 기존 Hikari 연결이 어떻게 실패·전환되는지는 알 수 없었다. 이 이슈는 **DB primary 장애가 아니라 프록시 한 대만 지속 중단**하는 독립 시험이다. 프로세스의 짧은 자동 재시작, 패킷 DROP, DB 리더 장애는 여기서 주장하지 않는다.

```text
그림 1 — 실제 시험 경로와 장애 경계

GCP 내부 부하 VM (k6, HTTP 100 req/s, 재시도 없음)
          │  request_id를 한 번만 전송
          ▼
내부 HTTP LB ───────────┬──────────────┐
                       ▼              ▼
                 백엔드 A         백엔드 B
                 Hikari 5        Hikari 5
                       └──────┬───────┘
                              │ pgJDBC 다중 호스트 URL
                    ┌─────────┴─────────┐
                    ▼                   ▼
             OpenProxy A           OpenProxy B
             DB 노드 2에 동거      DB 노드 3에 동거
                    └─────────┬─────────┘
                              ▼
               OpenSQL primary 1 + standby 2

실행 A: A만 중단, B 유지 → 복구
실행 B: B만 중단, A 유지 → 복구
DB와 앱·Redis VM은 중단하지 않음
```

LB가 2개 앱으로 HTTP를 분산한다는 사실과 JDBC가 2개 프록시를 가진다는 사실은 다르다. 여기서는 HTTP 수치로 앱 경로의 가용성을 보고, DB request ID로 201 이후의 실제 영속화를 확인한다. Redis와 GCS는 환경에 있지만 이 합성 쓰기 endpoint의 측정 대상은 아니다.

| 시험 환경 | 개수·상태 | 이 시험에서의 역할 |
| --- | --- | --- |
| OpenSQL DB VM | **3대**, primary 1·standby 2 | 테스트 행 영속화·역할 확인. 프록시는 standby와 같은 두 VM에 동거 |
| OpenProxy | **2개** | 앱 JDBC의 두 후보 주소. A/B를 한 번에 하나씩 중단 |
| 백엔드 VM | **2대**, 같은 JAR SHA-256 `c0180191121208d105f422cb64c57e325efc63a1c568c4c81a73d70cc7b8d1aa` | HTTP 쓰기, Hikari·JPA, probe API. 종료 후 health 200/200 |
| Redis VM | **1대** | 공용 앱 인프라. 부하 probe의 저장 대상은 아님 |
| 부하 VM | **1대** | k6 열린 도착률 100 req/s, 실행별 실시간 이벤트 |
| 내부 HTTP LB | **1개**, 종료 후 백엔드 **2/2 HEALTHY** | 동일 요청 경로 제공 |

## 사전 조건과 복구 안전장치

호스트의 OpenProxy는 systemd 서비스가 아니라 컨테이너 안의 단독 프로세스였다. 일반 `systemctl stop openproxy`를 가정하면 실제 장애가 주입되지 않는다. 그래서 [`openproxy_fault_guard.sh`](../../scripts/opensql/openproxy_fault_guard.sh)는 대상 컨테이너를 프록시 A/B로 제한하고, **먼저 호스트 systemd 복구 타이머를 무장한 뒤** 살아 있는 OpenProxy 프로세스 1개만 종료한다. 복구 시 OpenProxy의 작업 디렉터리를 명시하고, 단일 프로세스와 `pg_isready`를 모두 확인한다. DB 프로세스는 손대지 않는다.

```text
그림 2 — 실패한 리허설을 반영한 안전 가드

시험자: arm(run_id, 100초)
    │  └─ 호스트 systemd가 recover 타이머를 독립 보유
    │     AccuracySec=1s, 이후 30초 간격 재시도
    ▼
stop(): 현재 프록시 프로세스 1개·ready 확인
    │  가드가 pending일 때만 OpenProxy PID에 KILL
    ▼
OpenProxy down ────────┬───── 시험자 recover(): 정상 복구
                       └───── 시험자/SSH가 사라지면 타이머 recover()
                                            │
                                            ▼
             docker exec -d -u opensql -w <설치 디렉터리>
             → live process=1 ∧ pg_isready=ready 확인
             → 그때만 cancel() 허용

초기 리허설: 작업 디렉터리 누락으로 복구 실패 → 중단·수정
다음 리허설: 기본 timer 오차 때문에 44초 시점에도 down → 판정 보류
최종 리허설: AccuracySec=1s 후 45초 창에 자동 ready → 통과
```

리허설은 [별도 기록](evidence/issue-391/safety-rehearsals-20261003.md)에 실패까지 포함해 보존했다. **최종 리허설은 45초 이내 자동 복구 관측**이지 정확한 자동 RTO 35초 측정은 아니다. 실제 부하에서는 가드 100초를 유지한 채 약 1분 후 수동 복구했고, 두 실행 모두 가드를 안전하게 해제했다.

## 실행 순서·원장 구조

정상 실행은 60초, 장애 실행은 각각 130초였다. 출처 혼합을 막기 위해 세 실행을 별도 디렉터리·run ID로 분리했다. `ha_evidence.py init`을 부하보다 먼저 실행하고, k6는 요청마다 `sent`와 `acknowledged|failed|unknown`을 실시간 파일에 기록했다. 값 노출을 막기 위해 부하 VM 안에서 [`sanitize_ha_k6_events.py`](../../scripts/opensql/sanitize_ha_k6_events.py)로 허용 필드와 ID/시간 형식을 전부 검사한 뒤 안전 파일만 이동하는 절차다.

```text
그림 3 — 요청 1건의 판정과 증거 공백

k6: sent(request_id) ─HTTP POST─▶ 앱 ─JPA INSERT─▶ OpenSQL
       │                                              │
       └──── 응답 201 / 500 / 불명 ◀────────────────────┘

부하 VM 외부 이벤트                 DB primary 읽기 전용 내보내기
  request_id, HTTP 상태              request_id, row_count
              └──────────────┬───────────────┘
                             ▼
                   ID별 join 후 판정
        201 + count=1  → 성공 후 영속화 관측
        201 + count=0  → 성공 응답 후 누락
        201 + count>1 → 중복 반영
        500/불명       → DB 반영 여부를 따로 보고

정상·A: safe-events → 원장 import → finish → DB join 완료
B: safe-events는 부하 VM에만 보관, 전송 안전 검토에서 차단
   → 201과 DB 행의 총수는 같지만 ID별 join 미완료
   → B 내구성 PASS라고 쓰지 않음
```

| 목적·run ID | 실행 위치·명령의 공개형 | 결과 요약 | 판정·실행별 로그 |
| --- | --- | --- | --- |
| 정상 100 req/s · `ha391b100` | 부하 VM `run_ha_probe_k6.sh ha391b100 100 60s <내부 LB> <0600 토큰>`; primary `export_ha_probe_counts.sh` | **6,000/6,000 201**, 드롭/실패/불명 0. DB 6,000행, 누락/중복 0. p95 **51.68ms**, p99 **53.08ms** | **통과** · [기록](evidence/issue-391/ha391b100/실행-기록.md) · [대조](evidence/issue-391/ha391b100/reconciliation.json) |
| A 중단 · `ha391fa100` | 부하 VM `... 100 130s`; A 호스트 `arm → stop → recover → cancel`; primary DB 대조 | **13,001/13,001 201**, 실패/불명/드롭 0. DB 13,001행, 누락/중복 0. p95 **53.43ms**, p99 **56.73ms** | **관측 경로 통과** · [기록](evidence/issue-391/ha391fa100/실행-기록.md) · [대조](evidence/issue-391/ha391fa100/reconciliation.json) |
| B 중단 · `ha391fb100` | 별도 130초 k6·B 호스트 가드. ID별 대조는 미수행 | 13,001건 중 **12,991건 201**, **10건 500**. 드롭/불명 0. DB 총 12,991행·중복 ID 0. p95 **51.74ms**, p99 **92.71ms** | **내구성 보류** · [기록](evidence/issue-391/ha391fb100/실행-기록.md) · [k6 수치](evidence/issue-391/ha391fb100/k6-summary.json) |

같은 100 req/s·VM·JAR을 썼지만 정상 60초와 장애 130초의 실행 길이가 다르므로 p95 차이를 확정적 성능 개선/악화로 해석하지 않는다. k6의 `failed_rate`에는 B 실행의 500 열 건이 들어갔지만 임계값 1% 미만이라 k6 종료 코드는 0이다. **k6 종료 코드 0 ≠ 장애 무실패**다.

실제 GCP 실행 당시의 `run_ha_probe_k6.sh`는 로그 목적 라벨을 모든 실행에서 `정상 쓰기 기준선`으로 썼다. 실행 ID·장애 타임라인·분리된 외부 원장이 실제 목적을 구분한다. 이 PR에서 runner에 `baseline-write|proxy-a-fault|proxy-b-fault` 목적 인자를 추가하고 잘못된 인자 거부를 로컬에서 확인했지만, **새 runner를 GCP에 배포해 같은 부하를 재실행하지는 않았다**.

```text
그림 4 — B 장애 직후의 실제 시간 순서 (UTC, 초별 경계)

16:35:52   201 97건       B 중단 전
16:35:53   B down ─── 500 10건 (53.135~53.242)
                        같은 초의 201 79건
16:35:54   201 115건      500 추가 없음
16:35:55   201 99건
        ⋮   부하 지속, A 프록시 ready
16:36:52   B ready, 가드 해제

관측: 60개 장애 초 모두 201 응답이 존재했다.
추정: 기존 B 연결 일부가 끊기며 500이 났고 살아 있는 A 경로가 처리했다.
미확정: 실패 10건의 정확한 커넥션 선택·SQL 예외 원인,
       신규 커넥션이 A로 선택된 시각, 요청 ID별 DB 반영.
```

HTTP 전체가 멈춘 구간은 초 단위 집계에서 보이지 않았다. B 중단 초에 500 열 건이 집중됐고 그 다음 초에는 115건의 201이 있었다. 그러나 서버와 클라이언트 사이 장애 시각의 정밀도가 다르고, 동시 요청이 섞이므로 이 숫자만으로 **개별 요청 무중단이나 정확한 밀리초 RTO**를 주장하지 않는다. 이슈의 30초 RTO 판정 역시 새 연결·Hikari 상태를 고빈도로 함께 기록하지 않아 **정식 측정 미완료**다.

## 실패·복구·남은 판정

| 항목 | 관측 | 해석 또는 한계 |
| --- | --- | --- |
| B 실행 500 | 10건, 한 UTC 초 안에 집중 | 장애 직후 일시 오류가 존재. 앱 로그에서 같은 25초 창에 A/B 각각 `PSQLException` 포함 행 10줄을 관측했지만 로그 행 수와 HTTP 오류의 1:1 인과를 검증하지 않았다 |
| B DB 총량 | 201 12,991건과 DB 총 12,991행 | 수량은 일치하지만 ID별 누락·orphan 상쇄 가능성이 있어 성공 응답 보장은 아직 미확정 |
| B 비식별 이벤트 전송 | 허용 목록 검사 **26,002행 통과**, SHA-256은 [B 기록](evidence/issue-391/ha391fb100/실행-기록.md)에 남김 | B 전용 대상임을 확인했으나 안전 검토에서 A 자료 덮어쓰기 위험으로 두 번 차단. 우회하지 않음 |
| B 외부 원장 | 장애 시작·종료 이벤트까지만 있음 | `import-k6`, `finish`, `verify`, `reconcile`를 실행하지 않았음. 이 상태를 통과로 표시하지 않음 |
| 원복 | proxy A/B live 1·ready·guard absent, 앱 A/B health 200, LB 2/2 HEALTHY, DB primary 1·standby 2 | 합성 ADMIN 4계정 제거, 잔여 0·타이머 해제, 임시 JWT 양쪽 VM에서 삭제. 기존 VM 7대는 요청대로 실행 유지 |

비식별 압축 원본을 재생할 때는 각 실행 디렉터리에서 `*.gz`를 개별적으로 해제한 뒤 `ha_evidence.py verify`와 `reconcile_ha_probe.py`를 사용한다. [로컬 테스트 기록](evidence/issue-391/local-validation-20261003.md)에서 sanitizer 3/3, 기준선·A 원장 2/2 검증을 확인했다. 제품 버전은 과거 [설정·계약 시험](gimin-opensql-openproxy-routing-session-contract-20260926.md)의 값으로 manifest에 기록했으며 **이번 날짜에 각 바이너리 버전을 재조회한 결과로 취급하지 않는다**.

다음 판정 단계는 B 전용 비식별 이벤트와 DB ID별 CSV의 안전한 반출 경로를 승인·확정해 원장을 완성하는 것이다. 그 뒤 B 201 누락·중복과 500의 DB 반영 여부를 ID로 대조하고, 새 연결 도착 프록시/Hikari 상태를 별도 계측해야 이슈 #391의 모든 완료 조건을 닫을 수 있다.
