# OpenSQL 3노드 장애 시험 사전 게이트: 2026-10-03

관련: [이슈 #395](https://github.com/DocGrid/docgrid/issues/395), [기존 라우팅·설정 계약](gimin-opensql-openproxy-routing-session-contract-20260926.md), [프록시 프로세스 장애 결과](gimin-391-openproxy-ab-fault-load-20261003.md).

**판정: 실제 primary·etcd·패킷 DROP·VM 중단 장애 주입은 NO-GO.** 2026-10-03 03:40 KST의 읽기 전용 GCP 조회에서 DB VM은 **3/3 RUNNING**, 앱 LB는 **2/2 HEALTHY**였다. 그러나 VM 실행 상태는 Patroni 단일 리더·replica WAL 재생·etcd 정족수를 증명하지 않는다. live Patroni/etcd 상태와 검증된 etcd 백업·복구 절차를 확보하지 못했다. 연결된 DB 디스크의 Compute Engine 스냅샷 일치 건수는 **0**이지만, 이것만으로 etcd 내부 스냅샷 유무를 단정하지 않는다. 이 상태에서 장애를 주입하면 결과를 해석하거나 원복할 수 있다는 보장이 없다.

## 실제 경로와 확인 범위

```text
그림 1 — VM 실행과 DB 고가용성은 서로 다른 관측층

GCP Compute API                           DB 게스트 내부 (이번에는 미관측)
┌───────────────────────────┐           ┌──────────────────────────────┐
│ DB VM 3/3 RUNNING         │           │ Patroni: primary 1, replica 2?│
│ 앱 VM 2/2 RUNNING         │  같지 않음 │ etcd: member 3/3 healthy?    │
│ LB backend 2/2 HEALTHY    │ ────────▶ │ WAL receive/replay LSN?       │
└───────────────────────────┘           │ nofailover·복제 lag·timeline? │
                                        └──────────────────────────────┘

앱 LB HEALTHY는 HTTP 앱 상태이고 OpenSQL primary 선출 가능성의 증거가 아니다.
```

2026-09-26의 [설정 계약 증거](opensql-contract-evidence/contract-manifest.json)에는 당시 `ttl=30`, `loop_wait=10`, `maximum_lag_on_failover=1048576`, `failsafe_mode=true`, etcd 3멤버가 있다. 이는 **과거 수집값**이다. 이번 날짜의 live 설정·역할로 재인증하지 않았으며 `primary_start_timeout` 미설정을 0으로 해석하지 않는다. 2026-10-02의 프록시 KILL 시험 종료 시 DB가 primary 1·standby 2였다는 기록도 오늘의 실시간 역할 증거는 아니다.

## 이번에 실제 실행한 확인

모든 출력은 역할별 건수·상태·시험 숫자만 남겼다. GCP 이름·IP·프로젝트 ID, 로컬 컨테이너 ID, 비밀번호는 결과 문서에 기록하지 않았다. 명령의 `gcloud` JSON은 호출 중 메모리에서만 처리하고 식별자는 출력 전 제거했다.

| 목적·실행 위치 | 명령 또는 방법 | 숫자 결과·해석 | 별도 로그 |
| --- | --- | --- | --- |
| GCP VM·앱 LB 상태 · 로컬 `gcloud` 읽기 | `gcloud compute instances list --format=json`과 `backend-services get-health`의 허용 필드만 집계 | DB **3/3 RUNNING**, 앱 **2/2 RUNNING**, 부하 VM **1/1**, Redis VM **1/1**, 앱 LB **2/2 HEALTHY**. DB 내부 상태는 **미확인** | [토폴로지 실행](evidence/issue-395/cloud-topology-20261003.md) |
| DB 디스크 복구 증거 · 로컬 `gcloud` 읽기 | 실행 중인 DB VM의 부착 디스크 참조와 `gcloud compute snapshots list --format=json`의 source disk를 정확히 대조 | 연결 디스크 **3개**, 일치하는 Compute Engine snapshot **0건**. etcd snapshot은 별도 미확인 | [백업 조회](evidence/issue-395/backup-inventory-20261003.md) |
| DB 외부 요청 원장 · 로컬 Python | `python3 scripts/opensql/test_ha_evidence.py -v` | 합성 **11/11 통과**. 201·실패·결과 불명·중복·원장 변조 구분 가능. 실제 primary 장애 RPO는 **미측정** | [원장 시험](evidence/issue-395/ledger-local-20261003.md) |
| 프록시 복구 분석기 · 로컬 Python | `python3 scripts/opensql/test_analyze_ha_proxy_recovery.py -v` | 합성 **2/2 통과**. 500ms 표본 범위와 비허용 열 거부 확인. primary 장애 분석기라고 주장하지 않음 | [복구 분석 시험](evidence/issue-395/proxy-recovery-local-20261003.md) |
| Worker 복구 코드 · 로컬 JVM | lease scheduler·recovery service·indexing pipeline·Outbox recovery 4개 suite | **16/16 통과**, 실패·건너뜀 0. mock 기반이므로 실제 DB 리더 장애 결과가 아님 | [Worker 단위 시험](evidence/issue-395/worker-unit-local-20261003.md) |
| Outbox 원자성 · 격리된 localhost PostgreSQL 17 + pgvector | `test --tests ...SyncTransactionBoundaryIntegrationTest` | 최종 실행 **5/5 통과**. 앞선 두 준비 실패와 태그 제외로 인한 부분 실행은 별도 보존 | [준비 실패 1](evidence/issue-395/outbox-attempt-1-20261003.md) · [준비 실패 2](evidence/issue-395/outbox-attempt-2-20261003.md) · [부분 실행](evidence/issue-395/outbox-attempt-3-20261003.md) · [최종 실행](evidence/issue-395/outbox-final-20261003.md) |
| Worker lease 실제 SQL 동시성 · 같은 격리 DB | `claimConcurrencyTest --tests ...EmbeddingJobLeaseRecoveryIntegrationTest` | 최종 실행 **4/4 통과**. 기본 `test`가 `claim-concurrency` 태그를 제외하므로 전용 태스크 사용 | [초기 실행](evidence/issue-395/lease-initial-20261003.md) · [최종 실행](evidence/issue-395/lease-final-20261003.md) |

사용한 로컬 DB 이미지는 `pgvector/pgvector:0.8.1-pg17`이었다. 포트는 loopback에만 임시 할당했고, 저장소의 [vector 초기화 SQL](../../docker/postgres/init/001-enable-vector.sql)에 해당하는 확장을 만든 뒤 합성 JWT 테스트 키로 실행했다. 테스트 컨테이너는 **3/3 삭제 확인**했다. 이 로컬 DB는 OpenSQL 제품 빌드나 GCP 3노드가 아니다.

```text
그림 2 — 로컬 Worker 시험에서 검증된 범위

합성 만료 lease / Outbox 상태
       │
       ├─ 단위 테스트: 스케줄러 순서·실패 격리 (mock)
       │
       └─ 임시 PostgreSQL 17:
             ├─ FOR UPDATE SKIP LOCKED 후보·단일 회수  → 4/4 통과
             └─ 실패 trigger·DB transaction rollback → 5/5 통과

아직 없음: GCP primary 장애 중 실제 PDF·DOCX 업로드,
           Worker claim·임베딩·Outbox 복구의 최종 DB/GCS 대조.
```

## 장애 유형별 실행 게이트

| 장애 시험 | 이번에 확보한 전제 | 빠진 전제·판정 |
| --- | --- | --- |
| 계획 switchover · PostgreSQL 프로세스 종료 · 리더 VM 상실 | 외부 원장 합성 시험 11/11, DB VM 3/3 실행 | live Patroni/etcd/replica LSN·승격 자격, 복구 경로, 앱 내부 부하 실행 권한 확인 전까지 **NO-GO**. 세 유형은 서로 다른 실행이어야 함 |
| 리더 장애 중 Worker 인덱싱 | 로컬 코드 16/16, PostgreSQL 통합 9/9 | 실제 PDF·DOCX/GCS·BGE 환경의 시험 run ID, failpoint, 최종 청크·임베딩·Outbox 대조 미준비 → **NO-GO** |
| 백엔드 A/B 양방향 장애·LB 재편입 | 현재 LB 2/2 HEALTHY | 요청 ID 원장·A/B 실제 종료 및 VM 상실 복구 가드, 재편입 관측 미실행 → **장애 결과 없음** |
| OpenProxy 패킷 DROP·정상 종료 | 기존 PR #392의 프로세스 KILL 결과와 분석기 합성 시험 | packet DROP 자동 원복·관리 포트 제외 범위·요청 timeout 상한 확인 전까지 **NO-GO**. KILL 결과로 대신하지 않음 |
| etcd 한 멤버·두 멤버 상실 | 과거 계약에서는 3멤버·`failsafe_mode=true` | 오늘의 quorum·Patroni REST 상호 도달·etcd snapshot 무결성/복구 리허설 미확인 → **NO-GO** |

Patroni 공식 문서는 승격 후보가 REST로 접근 가능하고 `nofailover`가 없으며 허용 복제 지연 등 조건을 만족해야 한다고 설명한다. [Patroni REST API](https://patroni.readthedocs.io/en/latest/rest_api.html), [patronictl의 역할·LSN 표시](https://patroni.readthedocs.io/en/latest/patronictl.html). etcd 공식 문서는 snapshot 저장과 `etcdutl` 상태 확인을 복구 근거로 제시하고, 영구적인 다수 멤버 상실은 정상적인 멤버 제거로 복구할 수 없다고 경고한다. [etcd v3.6 유지보수](https://etcd.io/docs/v3.6/op-guide/maintenance/), [정족수 상실 설계](https://etcd.io/docs/v3.6/op-guide/runtime-reconf-design/). 문서의 현재 버전은 설치된 Patroni 4.0.5·etcd 3.6.5의 실제 설정값을 대체하지 않는다.

```text
그림 3 — 다음 장애 주입의 중단 게이트

실행 전 live 수집
  ├─ Patroni: leader 1 / replica 2 / timeline·receive/replay LSN
  ├─ etcd: 3/3 건강 / quorum 2 / failsafe 실제 값
  ├─ 후보: nofailover=false / 허용 lag / 복구 타임라인
  ├─ 백업: etcd snapshot 파일·해시·복구 리허설
  └─ 원장: run ID / 외부 이벤트 / 최종 DB 대조 / 독립 원복 가드
               │
          하나라도 증거 부족
               ▼
          장애 주입 중단 (이번 판정)

모두 확인되면: 한 장애 유형씩 실행 → 201 ID/DB join → 복제·원복 확인.
```

## 발견한 문제와 Fix 판단

처음 로컬 통합 시험의 V32 SQLSTATE `42704`는 임시 DB에 `vector` 확장을 만들지 않은 **시험 준비 오류**였다. 두 번째 컨텍스트 오류는 합성 `JWT_SECRET` 누락이었다. 이후 기본 Gradle `test` 태스크에서 lease 동시성 suite가 빠진 것은 기존 `claim-concurrency` 태그 제외 규칙 때문이었다. 격리 DB 초기화·환경 변수·전용 Gradle 태스크를 바로잡자 Outbox **5/5**, lease **4/4**가 각각 통과했다. **제품 코드 결함은 이번 로컬 실행에서 재현되지 않아 추측성 Fix는 만들지 않았다.** 실패한 시도와 마지막 결과는 합치지 않는다.

이번 문서는 사전 게이트·로컬 기준선 결과이며, primary 장애의 RTO/RPO, Worker 최종 복구, 앱 A/B 양방향 장애, 패킷 DROP, etcd 정족수의 **실행 결과가 아니다**. 최종 통합 보고서는 실제 장애별 원본 실행 ID가 쌓인 뒤 작성해야 한다.
