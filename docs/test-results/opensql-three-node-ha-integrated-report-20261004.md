# OpenSQL 3노드 HA·정합성 검증 통합 보고서

> 정리 기준: 2026-10-04 KST · 관련 이슈: [#437](https://github.com/DocGrid/docgrid/issues/437)
> 성격: **이미 실행한 시험의 증거를 연결한 문서**. 이 보고서를 위해 새 장애·부하를 주입하지 않았다.

## 결론

DocGrid의 GCP 시험 환경에서 OpenSQL 3노드와 OpenProxy 2개를 실제 앱 A/B의 DB 경로로 사용했다. 정상 PDF 저장·조회, OpenProxy 한쪽 중단, 계획·비계획 primary 장애, Worker 복구, etcd 정족수 상실, 비동기·동기 복제와 동기 standby 연결 상실까지 각각 분리해 측정했다. 여러 실행에서 **201을 받은 합성 쓰기 ID의 DB 누락·중복은 0건**이었다. 그러나 장애 중 HTTP 500·503과 결과 불명도 실제로 발생했다. 따라서 결론은 **“관측된 성공 응답의 정합성은 유지됐지만, 무중단 가용성이나 모든 상황의 RPO 0은 입증하지 못했다”**이다.

비계획 리더 VM 상실에서 **장애 기록 전 마지막 201 요청 시작부터 5초 연속 정상 구간 시작까지 약 52.9초**가 걸렸다. 동기 standby 복제 링크 장애의 **연속 201 응답 사이 최대 간격은 63.892초**였다. 앞의 값은 중간에 성공 요청도 포함할 수 있는 *안정화 관측 구간*이고 뒤의 값은 *성공 응답 간격*이므로 둘을 동일한 쓰기 중단 시간이나 공통 RTO로 비교하지 않는다. etcd 두 멤버를 약 100초 멈춘 실행에서는 `failsafe_mode=true` 아래 TTL 이후 보수적 구간의 앱 쓰기 **2,100건이 모두 201**을 받았다. 이 수치들도 서로 다른 장애·부하·설정에서 나왔으므로 처리량 지표로 합산하지 않는다.

## 시험 환경과 측정 경계

| 구성 | 시험 당시 역할 | 확인된 기준과 주의 |
| --- | --- | --- |
| OpenSQL DB | GCP VM 3대, 각 Rocky Linux 9.7 `x86_64` 컨테이너 1개 | 각 VM `e2-standard-2`, 컨테이너 논리 CPU 2개·메모리 제한 6 GiB. 역할은 시점마다 바뀌므로 node1을 영구 리더로 간주하지 않는다. [설치·라이선스 실행 기록](gimin-opensql-three-node-runtime-activation-20260923.md) |
| OpenProxy | DB 노드 중 두 곳의 프록시 A/B | 별도 프록시 VM 2대가 아니다. 설치 빌드 1.1.3 revision 723. 앱 JDBC는 두 주소를 사용하고 Flyway는 별도의 primary 탐색 경로를 사용한다. [계약 시험](gimin-opensql-openproxy-routing-session-contract-20260926.md)·[앱 정상 경로](gimin-387-gcp-app-gcs-opensql-baseline-20261002.md) |
| 제어면 | DB 노드 3곳의 Patroni·etcd | 계약 시험에서 Patroni 4.0.5, etcd 3.6.5, `ttl=30s`, `loop_wait=10s`, `failsafe_mode=true`, etcd 정족수 2/3을 수집했다. 장애별 사전 점검 범위는 원본 문서가 기준이며, 제품 바이너리 버전을 모든 실행 직전에 다시 측정한 것은 아니다. [설정 기록](gimin-opensql-openproxy-routing-session-contract-20260926.md)·[버전 미재조회 사례](opensql-http-500-fault-diagnosis-20261004.md) |
| 앱·파일·캐시 | 내부 LB 뒤 백엔드 VM A/B 2대, GCS, 공용 캐시 VM 1대 | 정상 PDF 시험에서 DB 기록·GCS 객체·다운로드 SHA가 일치했다. 해당 GCP 시험의 공용 캐시는 Redis였으며 **로컬 개발 환경의 Valkey 전환과 혼동하지 않는다**. [정상 경로](gimin-387-gcp-app-gcs-opensql-baseline-20261002.md) |
| 부하·증거 | GCP 내부 부하 VM 1대의 k6와 DB 밖 요청 원장 | DB 3 + 앱 2 + 캐시 1 + 부하 1 = **총 7대 VM**. LB와 GCS는 VM 수에 넣지 않는다. 장애 시험의 주 부하는 HTTP 합성 INSERT이며 실제 문서 인덱싱 부하는 별도 실행이다. [원장 설계](gimin-389-opensql-ha-http-write-baseline-20261002.md) |

```text
그림 1 — 실제 앱 쓰기와 문서 저장의 경계

GCP 내부 k6 또는 PDF 클라이언트
     │ HTTP 요청 + 실행별 request_id (k6는 응답을 DB 밖에 즉시 기록)
     ▼
내부 LB ──┬── 앱 A: 인증 → Hikari ──┐
         └── 앱 B: 인증 → Hikari ──┴──▶ OpenProxy A/B
                                            │ 트랜잭션 쓰기
                                            ▼
                                 OpenSQL primary ─ WAL ─▶ replica 2
                                      │
                          합성 쓰기 ID / 문서·Job 기록

PDF 정상 경로: 앱 → GCS 객체 저장 → OpenSQL 파일 메타데이터 기록
              앱 A·B·LB에서 다운로드 → 원본 SHA-256 대조
Flyway: 앱 일반 JDBC 경로와 분리된 primary 탐색 JDBC 경로
```

프록시 두 개는 **앱 연결 경로의 대체 대상**이고, standby 두 개가 모든 쓰기를 분산 처리한다는 뜻이 아니다. SQL 계약 시험에서는 자동 커밋·read-only 조회가 standby, 명시적 쓰기 트랜잭션과 `FOR UPDATE SKIP LOCKED`가 primary에 도달했다. 물리 standby별 분산 비율이나 backend 교체 후 prepared-statement 캐시 재준비는 아직 증명하지 않았다. [라우팅·세션 계약](gimin-opensql-openproxy-routing-session-contract-20260926.md)

## 증거를 판정하는 방법

부하 원장의 `request_id`와 최종 primary의 `ha_probe_writes` 행 수를 실행 ID별로 대조했다. HTTP 201, 500/503, 결과 불명(timeout·연결 종료), k6 미전송을 구별한다. `normal_baseline_pass=false`는 장애 중 실패 응답이 있었다는 뜻일 수 있으므로 201 누락으로 단정하지 않는다. **201 누락 0건도 이번 표본의 관측값이지 제품의 절대 RPO 보장이 아니다.**

```text
그림 2 — 응답과 최종 DB 상태를 분리하는 판정

DB 밖 원장(request_id, run_id, HTTP 결과)
   ├─ 201 ────────────▶ 최종 primary의 같은 ID가 1행: 보존 관측
   │                    0행: 성공 응답 데이터 누락 / 2행↑: 중복
   ├─ 500·503 ────────▶ 같은 ID가 0행인지 1행인지 별도 기록
   ├─ 결과 불명 ───────▶ 커밋됐을 수도 있으므로 자동 재시도 근거가 아님
   └─ k6 미전송 ───────▶ HTTP 요청 자체가 없어 DB 누락 분모에서 제외

주요 경로 A: run_ha_probe_k6.sh → ha_evidence.py import-k6/finish/verify
             → export_ha_probe_counts.sh → reconcile_ha_probe.py
후속 경로 B: DB의 request_id별 CSV → import_completed_ha_k6.py
             → ha_evidence.py verify (복제 모드·링크 장애 실행 등)
어느 경로를 썼는지는 실행별 원본 문서가 기준이다.
각 실행 ID의 요약·원본 CSV/JSONL은 아래 링크의 목적별 증거 폴더에 분리했다.
```

## 주요 실측 결과

아래 각 행은 **서로 다른 실행**이다. 부하율, 길이, 장애 경계가 같지 않으므로 행 간 p95·실패율을 직접 비교하지 않는다. 세부 시각·실행 명령·원복은 각 링크의 실행별 문서에서 확인할 수 있다.

| 시험·실행 ID | 실제 입력과 관측 결과 | 데이터·가용성 판정 | 근거 |
| --- | --- | --- | --- |
| 정상 앱·GCS `gcp387-pdf-a-r3`/`b-r1`/`lb-r1` | PDF 업로드 각 201, A/B/LB 다운로드 각 200×3·해시 3/3. DB 3행·GCS 3객체·해시 3개 일치 | 정상 기능 경로 통과. 장애·Worker 완료 증거는 아님 | [#388 정상 경로](gimin-387-gcp-app-gcs-opensql-baseline-20261002.md) |
| 정상 HTTP 쓰기 `ha389r150`/`r150b`/`r150c` | 각 150 req/s × 60초, 각 9,001건 전송·201, 미전송·실패 0 | 각 실행 201 누락·중복 0. 200 req/s 세 번째 실행은 **29건 미전송**이라 안정 기준선으로 쓰지 않음 | [#390 정상 부하](gimin-389-opensql-ha-http-write-baseline-20261002.md) |
| OpenProxy A 중단 `ha391fa20001` | 100 req/s × 130초, 13,000건 중 201 12,997·500 3; 마지막 오류 뒤 신규 요청 성공까지 22ms | 201 누락·중복 0, **HTTP 무오류는 실패**. 22ms는 정의된 표본의 회복 마커이지 보편 RTO 상한 아님 | [#392 프록시 A/B](gimin-391-openproxy-ab-fault-load-20261003.md) |
| OpenProxy B 중단 `ha391fb20001` | 같은 목표 부하, 13,001건 중 201 12,993·500 8; 마지막 오류 뒤 신규 요청 성공까지 105ms | 201 누락·중복 0, **HTTP 무오류는 실패** | [#392 프록시 A/B](gimin-391-openproxy-ab-fault-load-20261003.md) |
| 계획 switchover `ha412sw400c` | 70 req/s × 180초, 12,601건 전송·미전송 0, 201 12,516·500 85; 최대 연속 201 공백 6.204초 | 201 누락·중복 0. **미전송을 해결해도 500은 남음** | [#415 재시험](issue-412-opensql-switchover-vu-telemetry-20261004.md) |
| 500 단계 진단 `ha418sw00001`/`ha418px00001` | **별도 재시험**에서 switchover 500 89건 중 `TX_BEGIN` 79·`SQL_EXECUTE` 10, 프록시 A KILL 500 5건 모두 `SQL_EXECUTE` | 요청 ID별 실패 단계와 DB 미반영을 확인. 위 85건 및 프록시 3·8건의 원인을 소급 확정한 결과는 아님 | [#419 요청별 진단](opensql-http-500-fault-diagnosis-20261004.md) |
| postmaster SIGKILL `ha420proc001` | 30 req/s × 120초, 201 3,372·500 227·불명 2; 안정화 관측 구간 약 29.5초 | Patroni가 **같은 노드에서 재시작**, timeline 12 유지. 201 누락·중복 0, 불명 1건은 DB 반영. 29.5초는 연속 201 응답 간격이 아님 | [#421 비계획 장애](opensql-primary-unplanned-fault-load-20261004.md) |
| 리더 VM 즉시 상실 `ha420vm00001` | 30 req/s × 180초, 201 4,349·500 467·503 582·불명 3; 안정화 관측 구간 약 52.9초 | 새 primary 승격·timeline 12→13·옛 노드 replica 재합류. 201 누락·중복 0, 불명 1건 반영. **primary와 같은 VM의 프록시 A도 함께 상실** | [#421 비계획 장애](opensql-primary-unplanned-fault-load-20261004.md) |
| Worker `ha424fault003` | 장애 전 PDF 12 + DOCX 12 접수, 처리 중 postmaster 종료. 최종 24/24 `INDEXED`, 청크·활성 임베딩 36:36, 첫 Attempt 실패 뒤 재시도 성공 | 청크·임베딩·Outbox 멱등 키 중복 0. 약 5분 43초는 lease를 포함한 **Worker 최종 복구**, DB 쓰기 RTO가 아님 | [#426 문서 Worker](opensql-worker-primary-fault-20261004.md) |
| etcd 두 멤버 중단 `e428htwo01` | 30 req/s × 150초, 4,500/4,500 HTTP 201·미전송 0; TTL 뒤 보수적 구간 2,100건 201 | 최종 DB 4,500 ID 각각 1행. `failsafe_mode=true`의 이 실행 쓰기 지속 관측. 전 구간의 단일 리더 연속 증명은 아님 | [#430 HTTP failsafe](opensql-etcd-http-failsafe-20261004.md) |
| 비동기 ↔ 동기 정상 기준선 `sync431a01~03`/`s01~03` | 각 30 req/s × 60초 3회. 실행별 p95 단순 평균 비동기 52.84ms·동기 56.05ms | 총 10,804개 201 ID가 각각 DB 1행. 3회 순차 측정은 인과·최대 처리량의 증거가 아님 | [#432 복제 정상 기준선](opensql-sync-async-normal-write-20261004.md) |
| 동기 standby 복제 링크 차단 `sync433fault01` | 30 req/s × 180초, 5,401건 중 201 3,445·500 519·503 1,423·불명 14; 201 공백 63.892초 | 201 누락·중복 0, 불명 중 **5건 DB 반영**. 동기 대기 중 가용성 악화; 비동기 설정 복원 | [#434 링크 장애](opensql-sync-standby-link-fault-20261004.md) |
| 같은 유형의 계층 진단 `ha435fault01` | 30 req/s × 120초, 3,539건 전송·62건 미전송. 201 1,578·500 372·503 1,579·불명 10 | 201 누락·중복 0, 불명 **6건 DB 반영**. 500은 앱 응답·503은 LB 응답으로 3,539/3,539 대조 | [#436 500/503 진단](opensql-sync-standby-500-503-diagnosis-20261004.md) |

`ha420vm00002`의 5,401건 401은 시험 인증이 만료됐고 **장애 주입 0회**였으므로 VM 상실 결과에 포함하지 않았다. Worker의 첫 정상 시도 `ha424work001`은 기존 GCP 앱 A/B에 배포된 JAR에 Batch 요청 JSON 형식 수정 커밋 `32ccc58`이 없어 12/12 실패했고, 장애를 주입하지 않았다. 현재 소스로 다시 만든 **시험 전용 Worker JAR**의 정상 기준선을 통과한 뒤 장애 시험을 진행했으며, 기존 앱 A/B JAR는 교체하지 않았다. 첫 장애 실행 `ha424fault002`에서는 업로드 HTTP 500도 1건 있었다. Worker를 상시 켜기 전 A/B 배포 버전 일치 확인이 필요하다. 실패한 준비·무효 실행을 성공 사례로 합산하지 않는다. [비계획 장애 원본](opensql-primary-unplanned-fault-load-20261004.md)·[Worker 원본](opensql-worker-primary-fault-20261004.md)

프록시 중단의 앞선 실행은 A `ha391fa100`에서 500 **0건**, B `ha391fb100`에서 **10건**이었다. 표의 A 3건·B 8건과 차이가 있으므로 단일 실행을 일반적인 실패 건수로 주장하지 않는다. [프록시 반복 결과](gimin-391-openproxy-ab-fault-load-20261003.md)

## 장애가 계층을 통과할 때 일어난 일

```text
그림 3 — 비계획 리더 VM 상실의 관측 순서

장애 전: primary 1 + streaming replica 2 / etcd 3/3 / LB 앱 2/2
  │ k6가 30 req/s로 request_id를 보내고 응답을 외부 원장에 기록
  ▼
리더 VM --no-graceful-shutdown 중단 (같은 VM의 OpenProxy A도 상실)
  ├─ 기존 연결·진행 요청 일부 → HTTP 500·503·결과 불명
  │                             (이 실행의 503 반환 주체는 미계측)
  ├─ 남은 Patroni/etcd가 후보를 판단 → 새 primary 승격, timeline 12→13
  └─ 앱이 새 경로로 쓰기 재개 → 장애 전 마지막 201 요청 시작부터
                                안정 201 구간 시작까지 약 52.9초
  ▼
새 primary에서 201 ID 4,349개: 누락 0·중복 0
결과 불명 3개: 1개 DB 반영 → 맹목적인 HTTP 재전송은 위험
옛 VM: 첫 자동 재시작 가드 실패 → 수동 시작 후 replica로 재합류
```

**리더 선출**, **앱 쓰기 안정 복귀**, **옛 노드 재합류**는 다른 시간 경계다. 분석기의 `observed_write_gap_seconds` 약 52.9초는 *장애 기록 전 마지막 201 요청의 시작 시각*부터 실패 시작이 없는 5개 연속 1초 버킷 중 첫 버킷까지다. 장애 명령 기록은 20:41:12.128 UTC, 첫 실패 요청 시작은 20:41:28.569, 마지막 실패 요청 시작은 20:42:04.001이었다. 따라서 52.9초 전체에 걸쳐 쓰기가 연속 중단됐다는 뜻이 아니며, 첫 실패~마지막 실패의 약 35.4초도 실패가 발생한 **시각 범위**일 뿐 그 사이 성공 0건을 뜻하지 않는다. [분석 코드](../../scripts/opensql/analyze_ha_primary_fault.py)·[원본 시간축](opensql-primary-unplanned-fault-load-20261004.md)

옛 노드의 **계획 이전 후 자동 재합류**는 앞선 [PID 1 reaping init 수정·양방향 시험](gimin-401-patroni-auto-rejoin-20261003.md)에서 확인했다. 이 VM 상실 실행의 자동 재시작 가드는 실패해 운영자가 VM을 수동으로 시작했고, 이후 replica 재합류를 관측했다. 별도의 GCP Workflows 복구 가드는 무해한 시험 VM에서만 확인했으므로 위 리더 VM을 자동 재시작했다고 주장하지 않는다. [복구 가드 기록](opensql-vm-recovery-guard-20261004.md)

```text
그림 4 — 동기 standby 링크 장애가 앱·LB까지 전파된 경로

실제 주입: 선택된 sync standby의 복제 TCP만 차단
        │  (VM·Patroni REST·etcd·OpenProxy 포트는 그대로)
        ▼
primary COMMIT의 동기 확인 대기 ──▶ 앱 트랜잭션 대기 증가
        (계측·설정에서 추론)             (요청별 경로는 미확정)
        │                              Hikari A/B 각각 active 최대 5/5
        │                              pending 최대 A97/B72
        ├─ 앱이 응답한 500 × 372       연결 획득 timeout 증가 A253/B198
        └─ readiness에 DB 포함 ──(실패 경로는 추론)──▶ LB 0/2 healthy 표본 18/50
                              └─ LB가 반환한 503 × 1,579

최종 DB: 201 1,578개 각 1행 / 결과 불명 10개 중 6개 1행
       → 500·503의 반환 주체는 측정했지만 모든 500의 Java 예외는 미확정
```

500의 **371/372건**에서 LB 지연이 4~6초로 앱의 Hikari 5초 획득 timeout과 맞았고, **1건은 55.368ms**였다. 동기 커밋 대기 → 풀 포화 → DB 포함 readiness 악화라는 설명은 독립 계측과 설정이 뒷받침하는 **강한 추론**이다. 500의 각 요청이 어떤 Java 예외에서 끝났는지는 배포 JAR에 요청별 진단 코드가 없어 확정하지 못했다. Hikari 풀 확대나 readiness DB 제거, 멱등 키 없는 자동 재시도는 이 자료만으로 안전한 수정이라고 판단하지 않는다. [계층 진단과 원복](opensql-sync-standby-500-503-diagnosis-20261004.md)

이는 **동기 링크 실행 `ha435fault01`에 한정한 미확정 범위**다. 별도 [요청별 500 진단](opensql-http-500-fault-diagnosis-20261004.md)에서는 계획 이전 89건을 트랜잭션 시작 단계 79·SQL 실행 단계 10, 프록시 A KILL 5건을 SQL 실행 단계 5로 1:1 분류했다. 세부 소켓 원인과 기존 장애 실행의 개별 예외를 소급 확정한 것은 아니다.

## 정합성·Worker·제어면에서 확인한 경계

| 주제 | 재현·검증된 것 | 여전히 말할 수 없는 것 |
| --- | --- | --- |
| 권한 회수 | 로컬의 격리된 JVM 2개가 SSH 터널로 GCP OpenSQL에 연결하고 임시 Redis를 사용했다. 두 standby의 적용을 지연시킨 기존 경로에서는 회수 후 오래된 ADMIN을 읽어 `/admin/**`가 **200**이 됐다. HTTP 관리자 인가를 primary 트랜잭션으로 고정하고 같은 조건을 재실행하자 **403**, 역할 SQL primary 1/standby 0, Redis ADMIN 재저장 없음. [재현](opensql-permission-replica-lag-safety-20261001.md)·[수정 후](opensql-admin-role-primary-consistency-20261001.md) | GCP 앱 A/B·공용 캐시 경로의 동일 장애 재시험, 일반 API의 최신 읽기 보장, DB failover로 회수 커밋 자체가 사라질 때의 보장 |
| Worker·Outbox | 프로세스 장애 뒤 lease 만료와 재시도로 24개 문서 최종 `INDEXED`; 자연키·Outbox 멱등 키 중복 0. [실행](opensql-worker-primary-fault-20261004.md) | Outbox 이벤트의 외부 전달·소비 완료는 측정하지 않았다. 당시 이벤트는 `PENDING`이었고 리더 VM 상실·대형 문서는 별도 범위 |
| etcd·failsafe | 한 멤버 중단과 두 멤버 중단을 분리했고, 두 멤버 중단에서 etcd health 명령 실패·Patroni failsafe 로그·TTL 이후 HTTP 201을 관측. [DB 직접 probe](opensql-etcd-quorum-failsafe-20261004.md)·[앱 HTTP 경로](opensql-etcd-http-failsafe-20261004.md) | 장애 중 전 노드 역할을 고빈도로 연속 채집하지 않아 **매 순간 split-brain 0**을 엄밀히 증명하지 못했다. primary 격리 네트워크 분할도 안 했다 |
| WebSocket·공용 캐시 | 두 앱 VM에서 대시보드 갱신의 A→B/B→A 수신과 ADMIN 회수 후 관측 창의 추가 수신 0건을 확인. [양방향 갱신](gimin-382-dashboard-cross-backend-refresh-20261002.md) | Redis Pub/Sub 신호의 영속 전달·장시간 단절의 완전 복구와 모든 타이밍의 권한 회수 즉시성은 이 실행의 증거가 아님 |

## 복제 정책 판단과 미검증 항목

이번 환경의 원래 Patroni `synchronous_mode` 키는 **없었고**, PostgreSQL `synchronous_standby_names`도 비어 있어 **비동기 복제**였다. 정상 비교 시험과 두 동기 standby 링크 장애 시험에서 각각 일시적으로 `synchronous_mode=true`를 적용하고 sync 1/async 1을 확인했다. 각 원본은 종료 후 원래 비동기 설정 해시·leader 1·streaming replica 2·앱 LB 2/2 복귀를 기록한다. etcd 3/3은 링크 장애 **사전** 관문에서 확인했지만, 해당 두 실행의 문서에서 **종료 후 재조회까지 입증하지는 않는다**. 동기 정상 상태의 p95 평균 증가 약 3.21ms보다 **sync standby 상실 시의 쓰기 중단**이 이 구성의 더 큰 정책 질문이다. 단, 정상 모드 비교는 3회씩 순차 실행했으므로 3.21ms를 제품 고유 오버헤드로 단정하지 않는다. [정상 비교](opensql-sync-async-normal-write-20261004.md)·[동기 링크 장애](opensql-sync-standby-link-fault-20261004.md)·[계층 진단 반복](opensql-sync-standby-500-503-diagnosis-20261004.md)

| 후속 결정 | 이유와 필요한 증거 |
| --- | --- |
| OpenSQL 공급사에 quorum 동기 모드 지원 여부 문의 | [설치된 Patroni 4.0.5](gimin-opensql-openproxy-routing-session-contract-20260926.md)와 [기존 동기 모드 시험](opensql-sync-standby-link-fault-20261004.md)만으로는 공급사 빌드의 quorum 동기 모드 지원·권장 설정을 확인할 수 없다. 공급사 답변과 원복 절차를 확보하기 전에는 운영 3노드에 quorum 장애 시험을 적용하지 않는다. 특정 upstream 수정의 반영 여부나 설치 빌드의 결함은 검증하지 않았다. |
| 비동기 모드의 지연된 WAL 수신 + 리더 상실 | 기존 비동기 리더 상실의 201 누락 0은 지연을 의도적으로 만든 내구성 경계 시험이 아니다. 비동기 복제의 절대 RPO 0을 주장하지 않는다. |
| HTTP 500 하위 원인·멱등 재시도 정책 | [#419 별도 재시험](opensql-http-500-fault-diagnosis-20261004.md)에서 프록시·switchover의 요청별 실패 단계는 분류했지만 내부 소켓 원인과 [#436 동기 링크 실행](opensql-sync-standby-500-503-diagnosis-20261004.md)의 500별 Java 예외는 미확정이다. VM/동기 링크 장애의 결과 불명 일부가 DB에 반영돼 멱등 키 없는 자동 재시도는 위험하다. [이슈 #393](https://github.com/DocGrid/docgrid/issues/393)에서 정책을 별도로 결정해야 한다. |
| 추가 장애 유형 | 백엔드 A/B 프로세스·VM의 양방향 상실, OpenProxy 패킷 DROP·정상 종료, Worker의 리더 VM 상실과 Outbox 외부 전달, 장기 Redis Pub/Sub 단절은 이 통합 보고서에서 새로 시험하지 않았다. |

## 증거 관리와 최종 판정

실행별 한국어 로그·요약·비식별 원본은 해당 문서의 `evidence/issue-*` 링크를 따른다. 여러 시험의 원본 CSV/JSONL을 한 파일로 합치지 않았고, 초기 실패·무효 실행도 별도 기록으로 남겼다. 각 시험의 명령 위치·장애 주입 시각·원복 결과는 원 문서가 기준이며, **이 보고서 자체는 2026-10-04 시점의 교차 색인**이다. 새로운 실시간 클러스터 상태 조회나 전체 Gradle 테스트를 수행했다는 의미가 아니다.

**판정:** 설치·라이선스 실행, 앱–GCS–OpenSQL 정상 경로, 다수 장애에서의 요청 ID별 보존, Worker 최종 복구와 etcd failsafe의 앱 경로는 실제로 확인했다. 동시에 프록시·리더·동기 standby 장애 때의 HTTP 오류와 결과 불명, 동기 복제의 가용성 교환관계, 미검증된 quorum 지원·비동기 RPO 경계를 **실패·한계로 유지**한다. 발표에서는 “장애를 견디는 구성”과 “무중단·무손실 보장”을 같은 문장으로 쓰지 않는다.
