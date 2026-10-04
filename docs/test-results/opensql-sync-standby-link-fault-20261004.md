# OpenSQL 동기 standby 복제 연결 상실 시 쓰기 가용성

2026-10-04, 시험용 GCP 3노드에서 동기 복제 중 **현재 선정된 standby 한 대의 PostgreSQL 복제 연결만** 끊었다. 다른 standby가 존재해도 HTTP 쓰기는 즉시 연속 성공하지 않았다. 30 req/s·180초, 5,401건 중 **201 3,445건, 500 519건, 503 1,423건, 결과 불명 14건**이었다. 연속 201 응답 사이 가장 긴 공백은 **63.892초**였다. 반면 201 응답 3,445개는 최종 primary의 각 1행과 모두 일치해 관측 누락·중복이 0건이었다. 결과 불명 중 **5건은 DB에 반영**됐다. 이 결과는 장애 중 가용성과 이미 확인된 쓰기의 보존을 분리해야 함을 보여준다.

이 문서는 [이슈 #433](https://github.com/DocGrid/docgrid/issues/433)의 실행 결과다. 전체 standby VM 중단은 하지 않았으므로 그 장애 유형으로 일반화하지 않는다. 분당·반복 시험도 아니며 HTTP 500/503의 개별 스택 원인은 아직 규명하지 않았다.

## 실행 환경·안전 관문

| 항목 | 확인한 값·범위 |
| --- | --- |
| 계층 | DB VM 3대(primary 1·streaming replica 2), OpenProxy 2프로세스, 내부 LB 뒤 앱 VM 2대, GCP 내부 k6 VM 1대. 별도 공용 캐시 VM은 유지했으나 이 시험의 인가된 HTTP 쓰기 경로에서 캐시 장애는 주입하지 않았다 |
| 부하 경로 | k6 → 내부 LB → 앱 A/B → Hikari → OpenProxy A/B → OpenSQL primary; 합성 `ha_probe_writes` INSERT, 요청 ID를 DB 밖 원장에 기록 |
| 버전 | 이전 설치 검증 기준 OpenSQL 3.17.8.7·OpenProxy 1.1.3·Patroni 4.0.5·etcd 3.6.5. 이번 실행에서 제품 바이너리 버전을 새로 측정하지는 않았다 |
| 실행 전 | Patroni leader 1·streaming replica 2, 복제 지연 0MB, etcd endpoint 3/3 healthy, LB 2/2 HEALTHY |
| 복구 수단 | 기존 READY DB 디스크 snapshot을 확인. 현재 etcd snapshot을 만들고 `etcdutl snapshot status` 및 격리 디렉터리 restore로 읽기 가능성을 확인. 실제 운영 멤버 restore는 실행하지 않음 |
| 설정 변경 | 원래 Patroni 동적 설정에 `synchronous_mode` 없음. 호스트 독립 15분 자동 원복 타이머를 무장한 뒤 `synchronous_mode=true` 적용; PostgreSQL `synchronous_standby_names` 지정, streaming 2·sync 1·async 1을 직접 확인. `synchronous_mode_strict`는 설정되지 않음 |
| 장애 안전장치 | 현재 sync standby의 접속 주소만 primary 호스트 INPUT:5432에서 `REJECT tcp-reset`. 동일한 정확한 규칙을 제거하는 **독립 systemd 타이머 120초**를 먼저 무장·확인. etcd·Patroni REST·OpenProxy 포트·VM 자체는 차단하지 않음 |
| 실행 기준 | merged `develop` 커밋 `7bcd750b7f0547b8ea66dc14e377e30d93f05fdb`에서 분리한 시험 브랜치. 실제 앱 JAR이 정확히 이 소스 커밋에서 빌드됐다는 증거는 없음 |

```text
그림 1 — 시험한 시스템 경계

GCP 내부 k6 (request_id 생성·HTTP 결과를 DB 밖에 기록)
  │ 30 req/s, 180초; 201 / 500 / 503 / 결과 불명을 구별
  ▼
내부 LB ─────┬────▶ 앱 A ── Hikari ──┐
             └────▶ 앱 B ── Hikari ──┴──▶ OpenProxy A/B
                                              │ 쓰기 SQL
                                              ▼
                                       OpenSQL primary
                                      /               \
                             sync standby 1       async standby 2
                               ▲                      │
                               │ ★ 이 연결만 REJECT   │
                               └── primary:5432       │

장애 주입 위치는 primary 호스트의 선택된 복제 TCP 링크다.
standby VM·etcd·프록시 자체를 중단하는 시험이 아니다.
```

## 실행·명령·결과

| 위치 | 명령·절차 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| DB primary 컨테이너 | `patronictl list/show-config`, `pg_stat_replication`, `SHOW synchronous_standby_names` | leader 1·replica 2, 동기 지정 후 sync 1·async 1 | 이름만 바꾼 것이 아니라 DB에서 동기 standby 선정 확인 |
| etcd·DB 호스트 | `etcdctl snapshot save` → 설치 버전과 맞는 `etcdutl snapshot status/restore`; 디스크 snapshot READY 조회 | snapshot 2,543,648B·revision 7,361·10 keys; 격리 restore 성공 | 원복 수단 사전 점검. 운영 etcd member를 복구한 실험은 아님 |
| GCP 내부 부하 VM | `run_ha_probe_k6.sh sync433base01 30 30s <내부 LB> <보호된 JWT> sync-standby-link-fault 80` | 901건, 201 901·실패 0·미전송 0 | 동기 정상 상태의 바로 앞 관문 |
| DB primary 호스트 | `standby_link_fault_guard.sh arm … 120` → `inject`; 타이머·규칙 카운터 확인 | 10:10:21.705 UTC 차단, 규칙에 패킷 도착; 10:12:05.332 UTC 타이머가 자동 제거 | 실제 선택된 복제 연결에 작용. 정확히 120초 동안 차단된 것은 아님: 무장 후 주입했으므로 실제 약 104초 |
| GCP 내부 부하 VM | `run_ha_probe_k6.sh sync433fault01 30 180s <내부 LB> <보호된 JWT> sync-standby-link-fault 400` | 5,401건, 미전송 0; runner 종료 코드 99; 201 3,445·500 519·503 1,423·결과 불명 14 | 가용성 가설 실패. 종료 코드 99와 `normal_baseline_pass=false`는 장애 중 비정상 HTTP 결과를 반영하며 측정 미완료가 아님 |
| DB primary → 로컬 오프라인 | `COPY (SELECT request_id,count(*) … WHERE run_id=…) TO STDOUT WITH CSV HEADER` → `import_completed_ha_k6.py` → `ha_evidence.py verify` | 원장 complete, 201 누락·중복 0, 결과 불명 반영 5·미반영 9 | HTTP와 DB 최종 상태를 동일 요청 ID로 대조 |
| DB primary 호스트 | 자동 규칙 제거 확인 → `synchronous_mode=null` → `show-config | sha256sum` | 원래 SHA-256 `e1fadf809fbad93e696c678920fe6175e4b131e555e1c372f8d70d9535dbb5ee`와 동일; replica 2 streaming | 설정·복제 링크 원복. 이후 불필요한 두 자동 복구 타이머 해제 |
| GCP 내부 부하 VM·관리 API | 복원 후 별도 15초 HTTP smoke; DB ID 수 조회; LB health | 451건 모두 201·미전송 0, DB 고유 행 451; LB 2/2 HEALTHY | 정상 쓰기 재개 확인. 이 smoke의 원시 이벤트는 공개 증거로 복사하지 않아 요청 ID별 최종 대조로 주장하지 않음 |
| DB 3대·GCP 관리 API | 합성 ADMIN 제거·타이머 취소; 임시 SSH metadata 조회·제거 | 계정 0건·타이머 inactive, 다섯 VM의 인스턴스별 임시 SSH 키 0개 | 시험 전 접근·데이터 상태로 되돌림. 운영 VM은 후속 시험을 위해 실행 유지 |

```text
그림 2 — 동기 standby가 끊긴 동안 커밋이 겪은 경로

실행 전  primary ── WAL 확인 대기 ──▶ standby 1 (sync)
         standby 2 (async)는 연결되어 있으나 그 시점의 동기 확인 대상 아님

10:10:21.705  standby 1 → primary:5432 패킷 REJECT 시작
              │
              ├─ HTTP 500·503·결과 불명 발생
              ├─ 201 응답의 최대 연속 공백 63.892초
              └─ Patroni/PostgreSQL이 다른 standby를 sync로 선정
                    (10:11:57.599에 상태 확인; 실제 내부 변경 시각은 미수집)

10:11:25.553  201 재관측 ── 링크 자동 복구 전임
10:12:05.332  호스트 systemd 타이머가 정확한 REJECT 규칙 제거
10:12:28.924  standby 2대가 다시 streaming

관측된 원인·결과의 시간 연관은 강하지만, HTTP 500/503 각각의
예외 단계·LB 판단·DB 커밋 차단 여부는 로그 상관분석 전까지 단정하지 않는다.
```

## 결과와 판정

| 실행 ID | 계획 부하 | 전송 / 미전송 | 201 / 500 / 503 / 결과 불명 | 전체 HTTP p50 / p95 / p99 | DB 대조 |
| --- | --- | --- | --- | --- | --- |
| [`sync433base01`](evidence/issue-433/sync433base01/실행-결과.md) | 30 req/s × 30초 | 901 / 0 | 901 / 0 / 0 / 0 | 12.85 / 54.09 / 58.23ms | 201 누락·중복 0, 정상 관문 통과 |
| [`sync433fault01`](evidence/issue-433/sync433fault01/실행-결과.md) | 30 req/s × 180초 | 5,401 / 0 | 3,445 / 519 / 1,423 / 14 | 13.36 / 5,012.10 / 5,027.21ms | 201 누락·중복 0, 실패 반영 0, 불명 반영 5 |
| [`sync433post01`](evidence/issue-433/sync433post01/실행-결과.md) | 원복 후 30 req/s × 15초 smoke | 451 / 0 | 451 / 0 / 0 / 0 | 13.75 / 55.26 / 58.84ms | DB 고유 행 451; 원시 HTTP 이벤트는 공개본 미보존 |

```text
그림 3 — HTTP 원장과 최종 DB 대조의 서로 다른 의미

외부 원장(요청 ID 5,401개)             최종 primary: ha_probe_writes
  ├─ 201 3,445개 ──────────────────▶ 각각 정확히 1행 (누락·중복 0)
  ├─ 500/503 1,942개 ──────────────▶ 반영 0행
  └─ 결과 불명 14개 ───────────────▶ 5개는 1행, 9개는 0행

201 보존 관측  = 이번 장애에서 확인된 쓰기가 사라지지 않았다는 뜻
쓰기 가용성    = 별개; 1,942개 실패 + 14개 결과 불명으로 저하
재시도 위험    = 불명 5개는 이미 반영됐으므로 무조건 재시도하면 중복 가능
```

원장 시각 기준 첫 실패는 주입 0.129초 뒤인 10:10:21.834 UTC였다. 첫 503은 10:10:39.118, 마지막 500은 10:10:44.694, 마지막 503은 10:11:27.751이었다. 가장 긴 201 간격은 **10:10:21.661→10:11:25.553 UTC = 63.892초**. 마지막 결과 불명은 10:11:42.083이었다. k6 부하 발생기의 활성 VU 최대 160·확보 VU 400, 미전송 0건이므로 30 req/s 도착률 자체를 놓친 결과는 아니다. 부하 VM CPU·메모리 1초 원본은 실행별 증거에 있다.

## 결론·한계·후속 판단

1. **가용성은 떨어졌다.** 비엄격 동기 모드와 대체 standby 1대가 있어도 이 조건에서 무중단 쓰기는 관측되지 않았다. 현재 구성의 운영 요구가 짧은 쓰기 중단도 허용하지 않는다면 동기 standby 선정·연결 상실 반응, 앱 Hikari/OpenProxy/LB 오류 경로를 별도 진단해야 한다. 이번 `[Test]` 변경에는 위험한 자동 쓰기 재시도를 넣지 않았다.
2. **201 데이터는 보존됐다.** 이번 실행의 3,445개 201 응답에 한정한 최종 DB 관측이다. 3노드 전체의 절대 RPO 0이나 모든 종류의 standby 장애를 보장하지 않는다.
3. **결과 불명은 멱등성 문제가 된다.** 14건 중 5건이 DB에 반영됐다. 클라이언트·앱 재시도 정책을 바꾸려면 먼저 `request_id`의 원자적 멱등성을 설계해야 한다.
4. **원인 규명은 별도다.** HTTP 500/503의 정확한 예외 클래스·트랜잭션 단계, DB 커밋 대기, OpenProxy 서버 선택과 LB health 변화를 같은 시간축으로 추적하지 않았다. 현재는 실측된 결과만 주장한다. 전체 standby VM 상실, 두 standby 동시 장애, 리더 승격, 여러 부하율·반복 통계도 범위 밖이다.

원본 안전 이벤트·DB 대조·1초 계측·한국어 실행 로그는 [실행별 증거](evidence/issue-433)에 분리했다. `ha_evidence.py verify`는 두 원장의 `complete=true`를 확인했다. 공개 증거에는 내부 주소·프로젝트 ID·계정·JWT를 넣지 않았으며, 원본 k6 stderr는 사전 비식별 보장을 할 수 없어 수집하지 않았다. 시험 전용 JWT·ADMIN 4명·임시 SSH 키·격리 snapshot은 삭제했고, 원본 DB 디스크 snapshot과 시험용 VM은 유지했다.
