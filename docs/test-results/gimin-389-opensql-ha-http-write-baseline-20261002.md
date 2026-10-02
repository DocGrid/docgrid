# GCP OpenSQL HA HTTP 쓰기 원장·정상 부하 기준선 — 이슈 #389

2026-10-02 UTC에 **기존 GCP 시험 환경**에서 실행했다. 이 문서는 장애를 주입한 결과가 아니라, 이후 OpenProxy/primary 장애를 같은 잣대로 비교하기 위한 **정상 상태 기준선**이다. GCP 프로젝트 ID·내부 주소·계정·토큰·DB 암호는 수집 전에 결과 파일에서 제외했다.

## 핵심 결론

- **150 req/s × 60초 × 3회**에서 매번 HTTP 201 **9,001건**, k6 드롭·HTTP 실패·DB 누락·중복 **각 0건**이었다. p95는 **52.54~55.81ms**였다. 따라서 이후 장애 시험의 최초 부하를 이 관측상 안정 구간의 60~70%인 **90~105 req/s**로 제안한다.
- **200 req/s**는 3회 중 2회 통과했지만 3회차에 **29건 드롭**이 발생했다. **250 req/s**는 **2,201건 드롭**, p95 **794.66ms**였다. 둘 다 안정 기준선으로 사용하지 않는다. 드롭은 HTTP 요청이 시작되기 전 k6 스케줄에서 발생했으며 DB 유실 건수로 세면 안 된다.
- 실제로 발행되어 HTTP 201을 받은 **80,900건**은 외부 요청 원장과 DB를 대조했을 때 모두 **정확히 1행**이었다. 이는 **무장애 정상 부하의 관측 결과**이지, failover RPO=0 또는 제품 전체의 정확성 보장이 아니다.
- 내부 LB의 앱 A/B 누적 HTTP 201 카운터는 각각 **40,450 / 40,451**이었다. 합계 80,901에는 k6 80,900건과 A에서 직접 수행한 인증 smoke 1건이 포함된다. 두 백엔드는 종료 후 모두 `HEALTHY`, Hikari pending은 **0 / 0**으로 돌아왔다.

## 테스트 환경과 측정 경계

| 구성 요소 | 수량·사양 | 이 시험에서의 역할 | 관측/한계 |
| --- | --- | --- | --- |
| 백엔드 앱 VM A/B | 2대, 각 `e2-standard-2` | 동일 JAR·`opensql-ha,ha-probe` 프로필, 내부 HTTP LB 뒤 | 두 앱의 201 카운터 증가 확인. Worker·Sync Dispatcher·Reconciliation은 둘 다 `false`. |
| Redis VM | 1대, `e2-small` | 두 앱의 공용 인증 캐시 경로 | 연결 설정은 유지했으나 Redis 처리량·장애는 이번에 따로 계측하지 않음. |
| OpenSQL DB VM | 3대, 각 `e2-standard-2`; 컨테이너는 Rocky Linux 9.7 `x86_64` | primary 쓰기와 standby 복제 구조 | 이 시험에서는 primary에 도착한 커밋 행만 대조. standby 읽기·failover 미시험. |
| OpenProxy | 기존 2개 | 앱 JDBC 설정의 프록시 경로 | 프록시별 쿼리 수·프로세스 장애는 이번에 계측하지 않음. |
| 부하 VM | 1대, `e2-standard-2`, k6 `v2.3.0` | GCP 내부에서 열린 모델(`constant-arrival-rate`)로 HTTP 요청 | 클라이언트 이벤트를 발생 즉시 실행별 0600 JSONL에 기록. 부하 VM 자체의 CPU 상한은 별도 분리 시험 전. |
| 내부 HTTP LB | 기존 1개 | 요청을 A/B로 전달 | 최종 상태 2/2 `HEALTHY`; A/B 누적 카운터가 거의 동일. |

OpenSQL `v3.17.8.7`, OpenProxy `1.1.3-r723`, Patroni `4.0.5`, etcd `3.6.5`는 [기존 설치 빌드 확인 문서](opensql-contract-verification/product-and-driver-versions.md)의 2026-09-26 값이다. 원장에는 재현 참조값으로 적었으며, **이 날짜에 버전을 다시 수집했다고 주장하지 않는다.**

```text
그림 1 — 이번에 실제로 부하를 건 경로

GCP 내부 k6 VM
  ├─ 매 iteration마다 run_id + request_id 생성
  ├─ sent 이벤트를 외부 파일에 즉시 기록
  └─ POST /api/ha-probe/writes + 단기 합성 ADMIN JWT
                    │
                    ▼
          내부 HTTP 로드밸런서
             ├──────────────┐
             ▼              ▼
        앱 A (2 vCPU)   앱 B (2 vCPU)
             │              │
             ├─ JWT·역할 확인 ┤──────▶ 공용 Redis (캐시·블랙리스트)
             │              │
             └──────┬───────┘
                    ▼
               Hikari → JPA 트랜잭션
                    ▼
         설정된 OpenProxy A/B 주소 → OpenSQL primary
                                      ├─ ha_probe_writes INSERT
                                      └─ commit 완료 뒤 HTTP 201

이번에 관측한 A/B 분산 = HTTP 201 카운터 40,450 / 40,451
이번에 관측하지 않은 것 = 프록시 A/B별 라우팅, standby 읽기, 장애 전환
```

앱 A/B 모두 기존 설정의 `opensql-ha` 경로를 사용한다. 다만 그림의 OpenProxy A/B는 **설정 토폴로지**이고, 이 실험만으로 각 프록시의 실시간 처리 비율을 입증하지는 않는다. PDF·GCS·BGE-M3·Worker도 이 부하에는 포함되지 않는다.

## 무엇을 추가했고 왜 필요한가

| 코드·파일 | 기능 | 안전 경계 |
| --- | --- | --- |
| [`HaProbeWriteController`](../../backend/src/main/java/com/opensource/docgrid/domain/failover/controller/HaProbeWriteController.java), [`HaProbeWriteService`](../../backend/src/main/java/com/opensource/docgrid/domain/failover/service/command/HaProbeWriteService.java) | HTTP 요청을 JPA 트랜잭션의 INSERT에 연결하고 커밋 완료 뒤 201 반환 | `ha-probe` 프로필 **및** `docgrid.ha-probe.enabled=true`가 모두 필요. 일반 프로필에는 mapping 없음. |
| [`SecurityConfig`](../../backend/src/main/java/com/opensource/docgrid/global/config/SecurityConfig.java) | `/api/ha-probe/**`에 ADMIN 인가 | 무인증 실서버 smoke 401, 로컬 비ADMIN 회귀 403. |
| [`V44`](../../backend/src/main/resources/db/migration/V44__create_ha_probe_writes.sql) | `run_id`·`request_id`·시각 저장 | `request_id`를 UNIQUE로 숨기지 않아 물리적 중복 INSERT를 대조에서 발견할 수 있다. 테이블은 남지만 엔드포인트는 프로필로 차단 가능. |
| [`ha_probe_load.js`](../../scripts/opensql/ha_probe_load.js), [`run_ha_probe_k6.sh`](../../scripts/opensql/run_ha_probe_k6.sh) | k6 열린 모델, 201/실패/불명 구분, 실행별 실시간 JSONL·숫자 요약 | HTTP 대상·JWT·응답 본문은 로그에 기록하지 않는다. 원시 k6 stderr는 사전 비식별화가 불확실해 수집하지 않았다. |
| [`ha_evidence.py`](../../scripts/opensql/ha_evidence.py), [`reconcile_ha_probe.py`](../../scripts/opensql/reconcile_ha_probe.py) | 대량 이벤트를 한 번에 검증·외부 원장에 넣고, DB request ID별 행 수와 대조 | `durable_write_pass`와 `load_schedule_pass`를 **별도로** 판정한다. |
| [`deploy_ha_probe_vm.sh`](../../scripts/opensql/deploy_ha_probe_vm.sh), [`export_ha_probe_counts.sh`](../../scripts/opensql/export_ha_probe_counts.sh) | 기존 VM 배포·복구 가드 및 primary의 run ID별 안전한 CSV 내보내기 | JAR SHA 검증, 기존 설정 백업, health 200 확인. DB 내보내기는 요청 ID와 숫자만 출력한다. |

```text
그림 2 — 요청 하나의 판정 순서와 원장 대조

k6 iteration v7-i19
  1. request_id=ha389r150-v7-i19 생성
  2. sent(at=...) ───────────────────────▶ k6-events.jsonl (DB 밖)
  3. POST + JWT ────────────────────────▶ LB → 앱 A 또는 B
       앱: SecurityFilterChain에서 ADMIN 검사
         → HaProbeWriteController.write()
         → HaProbeWriteService.write()
         → JPA INSERT → 트랜잭션 commit
  4. HTTP 201 ←───────────────────────── 앱
  5. acknowledged(at=..., status=201) ─▶ k6-events.jsonl

실행 후:
  k6 이벤트 → events.jsonl → requests.csv (외부 원장)
  primary SELECT run_id별 request_id, count(*) → db-counts.csv
  요청 ID join:
    201 + DB count=1  → 관측상 정상
    201 + DB count=0  → 성공 응답 후 누락
    201 + DB count>1 → 중복 커밋
    결과 불명/실패     → 성공과 별도 분류
    k6 dropped       → 3번 HTTP를 시작하지 못함; DB 유실이 아님
```

부하 발생기는 **자동 재시도하지 않는다**. 따라서 이번 중복 0건은 이 시험의 단일 시도 조건에 한정된다. 네트워크 결과 불명 후 재시도하는 멱등 정책은 뒤따르는 장애 시험에서 별도로 정의해야 한다.

## 실행 방법과 실제 결과

각 실행 전 안전한 JSON 설정 파일의 SHA-256으로 별도 원장을 초기화했다. 실제 내부 URL·토큰 파일 경로는 공개 명령에서 치환했다. 예시는 **실행 명령의 비식별화 표기**다.

```bash
python3 scripts/opensql/ha_evidence.py init --run-dir <run-dir> --run-id ha389r150 \
  --scenario baseline-write --config-sha256 <redacted-config-sha256> \
  --opensql-version v3.17.8.7 --openproxy-version 1.1.3-r723 \
  --patroni-version 4.0.5 --etcd-version 3.6.5

# GCP 내부 부하 VM에서 실행. URL·0600 토큰 파일의 값/경로는 공개하지 않는다.
bash scripts/opensql/run_ha_probe_k6.sh ha389r150 150 60s \
  'http://<internal-lb>/api/ha-probe/writes' '<0600-jwt-file>'

python3 scripts/opensql/ha_evidence.py import-k6 --run-dir <run-dir> --source <safe-k6-jsonl>
python3 scripts/opensql/ha_evidence.py finish --run-dir <run-dir>
# primary VM의 보호된 PostgreSQL 컨테이너에서 실행해 request_id,row_count만 출력
sudo bash scripts/opensql/export_ha_probe_counts.sh <primary-container> ha389r150
python3 scripts/opensql/reconcile_ha_probe.py --run-dir <run-dir> --db-csv <safe-db-csv>
```

실행 시각·k6 종료 코드·샘플 수·원시 로그 미수집 사유는 각 행의 **한국어 실행 기록**에 있다. 시간대는 UTC다. p95/p99는 **각 실행의 HTTP 요청 지연 분포**이며, 아래 10개의 p95 값을 표본 10개의 p95로 다시 계산하지 않았다.

| run ID / 목적 | 목표·시간 | 실제 시작한 HTTP / 드롭 | HTTP 실패 | p95 / p99 (ms) | 201→DB 누락 / 중복 | 판정·실행별 증거 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `ha389s01` smoke | 2 req/s·10초 | 21 / 0 | 0 | 72.18 / 91.47 | 0 / 0 | 통과. [실행 기록](evidence/issue-389/ha389s01/실행-기록.txt) · [요약](evidence/issue-389/ha389s01/reconciliation.json) |
| `ha389r020` | 20 req/s·30초 | 601 / 0 | 0 | 57.44 / 61.73 | 0 / 0 | 통과. [실행 기록](evidence/issue-389/ha389r020/실행-기록.txt) · [요약](evidence/issue-389/ha389r020/reconciliation.json) |
| `ha389r075` | 75 req/s·60초 | 4,501 / 0 | 0 | 53.43 / 55.05 | 0 / 0 | 통과. [실행 기록](evidence/issue-389/ha389r075/실행-기록.txt) · [요약](evidence/issue-389/ha389r075/reconciliation.json) |
| `ha389r150` (1/3) | 150 req/s·60초 | 9,001 / 0 | 0 | 55.81 / 62.63 | 0 / 0 | 통과. [실행 기록](evidence/issue-389/ha389r150/실행-기록.txt) · [요약](evidence/issue-389/ha389r150/reconciliation.json) |
| `ha389r150b` (2/3) | 150 req/s·60초 | 9,001 / 0 | 0 | 53.29 / 56.01 | 0 / 0 | 통과. [실행 기록](evidence/issue-389/ha389r150b/실행-기록.txt) · [요약](evidence/issue-389/ha389r150b/reconciliation.json) |
| `ha389r150c` (3/3) | 150 req/s·60초 | 9,001 / 0 | 0 | 52.54 / 53.93 | 0 / 0 | 통과. [실행 기록](evidence/issue-389/ha389r150c/실행-기록.txt) · [요약](evidence/issue-389/ha389r150c/reconciliation.json) |
| `ha389r200` (1/3) | 200 req/s·60초 | 12,001 / 0 | 0 | 65.14 / 79.89 | 0 / 0 | 단일 실행 통과. [실행 기록](evidence/issue-389/ha389r200/실행-기록.txt) · [요약](evidence/issue-389/ha389r200/reconciliation.json) |
| `ha389r200b` (2/3) | 200 req/s·60초 | 12,001 / 0 | 0 | 64.49 / 77.63 | 0 / 0 | 단일 실행 통과. [실행 기록](evidence/issue-389/ha389r200b/실행-기록.txt) · [요약](evidence/issue-389/ha389r200b/reconciliation.json) |
| `ha389r200c` (3/3) | 200 req/s·60초 | 11,972 / **29** | 0 | 322.85 / 356.17 | 0 / 0 | **부하 스케줄 실패**. [실행 기록](evidence/issue-389/ha389r200c/실행-기록.txt) · [요약](evidence/issue-389/ha389r200c/reconciliation.json) |
| `ha389r250` | 250 req/s·60초 | 12,800 / **2,201** | 0 | 794.66 / 1,454.95 | 0 / 0 | **부하 스케줄 실패**. [실행 기록](evidence/issue-389/ha389r250/실행-기록.txt) · [요약](evidence/issue-389/ha389r250/reconciliation.json) |

각 실행 디렉터리는 `manifest.json`, `summary.json`, `k6-summary.json`, `reconciliation.json`, 한국어 `실행-기록.txt`, 원본 `k6-events.jsonl.gz`, 검증 원장 `events.jsonl.gz`, 파생 `requests.csv.gz`, DB 대조 `db-counts.csv.gz`를 분리 보관한다. 네 압축 파일은 비압축 원본과 `cmp`가 모두 일치했고 `gzip -t` **40/40** 통과했다. 압축 해제 후 [`ha_evidence.py verify`](../../scripts/opensql/ha_evidence.py)를 다시 실행할 수 있다. 원장 검증도 **10/10** 통과했다. 로컬 코드 검증과 전체 회귀 한계는 [별도 로그](evidence/issue-389/local-validation-20261002.md)에 있다.

첫 `ha389s01`은 탐색용 smoke로, k6의 안전한 실시간 이벤트 파일을 먼저 기록한 뒤 외부 원장으로 가져왔다. 해당 manifest의 시작 시각은 원시 실행 기록의 시각으로 보정했으므로, 이 실행의 manifest 자체를 **시험 전 독립적으로 확정된 원장**이라는 증거로 사용하지 않는다. 나머지 부하 실행은 원장을 먼저 초기화했고, 각 manifest의 시작 시각은 부하 시작이 아니라 원장 준비 시각일 수 있다. 정확한 부하 시작·종료는 실행별 한국어 기록을 기준으로 한다.

```text
그림 3 — 실제로 관측된 처리량 경계 (각 칸은 독립 60초 실행)

요청률       실행 1              실행 2              실행 3
150 req/s   9,001/9,001 · 0 drop  9,001/9,001 · 0 drop  9,001/9,001 · 0 drop
            p95 55.81ms          p95 53.29ms          p95 52.54ms

200 req/s   12,001 · 0 drop      12,001 · 0 drop      11,972 · 29 drop
            p95 65.14ms          p95 64.49ms          p95 322.85ms

250 req/s   12,800 HTTP 시작 / 2,201개 시작 전 drop
            p95 794.66ms · p99 1,454.95ms
            A/B Hikari pending 스냅샷 73 / 78
            시험 후 pending 0 / 0, LB HEALTHY 2 / 2

해석: 201을 받은 쓰기의 내구성과 계획한 부하를 모두 소화했는지는 별개다.
      250의 DB 누락 0건을 이유로 250 req/s를 정상 처리량으로 선언하지 않는다.
```

250 req/s 도중 Hikari pending 73/78을 한 번 관측했다. 이는 앱 쪽 대기도 존재했다는 근거지만 **연속 최대값이 아니고**, k6의 `maxVUs=160`·부하 VM CPU와 DB/프록시 병목을 각각 분리하지 못했다. 따라서 2,201 드롭의 단일 원인을 확정하지 않는다. 225 req/s 실행은 200 req/s 반복 실패를 확인한 뒤 **실행하지 않았으며**, 결과 행이나 원본 로그를 만들지 않았다.

## 문제 발생과 교정

| 시점 | 실제 관측 | 원인·수정 | 최종 판정 |
| --- | --- | --- | --- |
| 첫 인증 smoke | 무인증 401, 합성 ADMIN 요청 500 | `.env`에는 `ha-probe`를 추가했지만 systemd unit의 기존 `SPRING_PROFILES_ACTIVE=opensql-ha`가 우선 적용돼 컨트롤러가 등록되지 않았다. 배포 스크립트에 **unit 백업·수정·daemon-reload·헬스체크·롤백**을 추가했다. | A/B의 실제 활성 프로필 2개 확인 후 인증 요청 201, LB 2/2 HEALTHY. 첫 500은 성능 표본에 넣지 않음. |
| 로컬 비활성 경로 테스트 | HTTP 404 기대였으나 500 | 프로젝트의 전역 `NoResourceFoundException` 처리 때문에 HTTP 상태가 매핑 부재의 안전한 지표가 아니었다. Spring `RequestMappingHandlerMapping`의 실제 등록 여부를 검사하도록 테스트 수정. | 관련 웹 테스트 재실행 통과. 전역 예외 처리 자체는 이번 범위에서 수정하지 않음. |
| 첫 250 req/s 대조 | DB 대조만으로는 `normal_baseline_pass=true`라고 잘못 표시 | 이미 시작된 12,800개가 모두 저장돼도 예정된 2,201개는 시작되지 못한다. 판정기를 `durable_write_pass`와 `load_schedule_pass`로 분리하고 드롭 회귀 테스트를 추가했다. | 250과 200 3회차는 `durable_write_pass=true`, `normal_baseline_pass=false`로 재평가. |

## 정리와 다음 시험의 조건

합성 ADMIN fixture 1차는 독립 타이머가 자동 삭제했고, 2차는 실행 종료 후 명시적으로 제거했다. 각 회차에서 계정 잔여 **0건**을 확인한 뒤 타이머를 해제했다. 임시 JWT 파일과 추가 `.env` 백업 복사본은 A/B/부하 VM에서 삭제하고 부재를 확인했다. 반면 **기존 VM 7대와 앱·Redis·OpenSQL 컨테이너는 중지하지 않았고**, 다음 장애 시험을 위해 현재 앱 JAR·시험 프로필, run ID별 probe DB 행과 GCP 부하 VM의 실행별 로그를 남겼다. 시험 프로필은 관리자에게만 열리지만, 시험 기간이 끝나면 비활성화해야 한다.

다음 장애 시험은 **100 req/s**로 시작하는 것이 보수적이다. 이는 이번에 세 번 통과한 150 req/s의 약 **67%**다. 먼저 같은 100 req/s의 정상 실행을 같은 데이터 상태에서 한 번 더 확인한 뒤, OpenProxy A/B 중 한 대를 중지하는 시험으로 넘어간다. 이 문서에는 프록시·primary 장애, 요청 결과 불명 뒤 재시도, Worker 멱등 복구, 실제 PDF/GCS, standby 읽기 분산의 결과가 **없다**. 또한 로컬 전체 Gradle 테스트는 PostgreSQL 시험 포트가 닫혀 1,238건 중 124건이 실패했으므로 전체 통과라고 주장하지 않는다.
