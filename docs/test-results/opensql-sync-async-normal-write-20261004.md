# OpenSQL 비동기·동기 복제 정상 쓰기 기준선

2026-10-04 GCP 시험 클러스터에서 **같은 앱·부하 발생기·요청 경로·도착률**로 비동기 3회와 동기 3회의 HTTP 쓰기를 측정했다. 각 실행은 30 req/s × 60초이며, 모드별 30초 워밍업은 비교에서 제외했다. 201 응답을 받은 **총 10,804개 요청 ID가 각각 최종 primary DB의 정확히 1행**과 일치했다. 여섯 실행 모두 HTTP 실패·결과 불명·k6 미전송·DB 누락·중복이 0건이었다. 이것은 **정상 운영 기준선**이지 리더 장애·동기 standby 장애 때의 RTO/RPO 시험이 아니다.

## 시험 환경과 판정 기준

| 계층 | 이번 실행 환경 | 판정에 사용한 증거 |
| --- | --- | --- |
| 부하 | GCP 내부 k6 VM 1대, 30 req/s, 60초, 초기 VU 80·최대 160 | 실행별 원장·k6 summary·1초 VM 계측 |
| 애플리케이션 | 내부 LB 뒤 백엔드 A/B 2대, 실행 JAR SHA-256 동일(`c0180191121208d105f422cb64c57e325efc63a1c568c4c81a73d70cc7b8d1aa`) | LB 최종 HEALTHY 2/2 |
| DB 경로 | Hikari → OpenProxy A/B 2대 → OpenSQL DB 3대 | primary 1·streaming replica 2, 시험 전후 복제 지연 0MB |
| 제품 | OpenSQL v3.17.8.7, OpenProxy 1.1.3, Patroni 4.0.5, etcd 3.6.5 | 설치된 구성과 이전 검증 기록에 근거 |
| 증거·스크립트 기준 | `d55512bdd9737d8cb185304795f94854297f282d` (#430 머지 후 `develop`) | runner SHA-256 `cee44dedb1223c5cd5f1960f26e0fada8bcf92b8408e2ff51c9ac60841214987`; k6 JS `f47567887d495cb782722f5780271bfd33dac516827a3b9f68ff2535943f0eb3`; 1초 계측기 `84e63cf3968fe9f0e163782553d4e9c068b4559887f3791f1adbbb60a60114d2` |

성공 기준은 실행마다 **미전송 0, 201 이외 응답 0, 결과 불명 0, 201 ID의 DB 누락·중복 0, 원장과 1초 계측 완료**였다. 실험용 ADMIN 4명과 단기 JWT를 사용했고, 계정·키·내부 주소는 원본 공개 증거에 넣지 않았다.

```text
그림 1 — 두 모드에서 동일하게 유지한 애플리케이션 경로

GCP 내부 k6 (30 req/s)
    │  request_id마다 HTTP POST, 응답과 시각을 DB 밖에 기록
    ▼
내부 LB ──┬── 백엔드 A ─ Hikari ─┐
          └── 백엔드 B ─ Hikari ─┴── OpenProxy A/B
                                         │
                                         ▼
                            OpenSQL primary (INSERT·COMMIT)
                              ├── standby 1: WAL 수신·재생
                              └── standby 2: WAL 수신·재생

바뀐 것은 Patroni의 synchronous_mode 한 키뿐.
앱 JAR·LB·k6 코드·요청률·VU·3노드 수는 동일하게 유지했다.
```

## 안전 관문과 모드 변경

시작 직전 Patroni는 leader 1대·streaming replica 2대(지연 0MB), etcd는 3/3 healthy, 앱 LB는 2/2 HEALTHY였다. 실제 DB에서 `synchronous_standby_names`가 비어 있고 `pg_stat_replication`은 streaming 2·sync 0·async 2였다. Patroni의 `synchronous_mode` 키는 없었으며, `synchronous_commit=on`이어도 **동기 standby 지정이 없으므로 비동기 복제 상태**였다.

현재 etcd snapshot을 생성했고, 설치된 `etcdctl snapshot status`가 지원되지 않는 문제를 확인한 뒤 공식 etcd 3.6.5 `etcdutl`로 snapshot 상태(revision 7061·10 keys)를 읽고 **격리된 새 디렉터리로 복원**했다. 복원된 인스턴스를 서비스로 시작한 시험은 아니다. 원래 Patroni 설정 SHA-256 `e1fadf809fbad93e696c678920fe6175e4b131e555e1c372f8d70d9535dbb5ee`를 저장하고, 로컬 세션과 독립적인 30분 후 비동기 원복 systemd 타이머를 무장했다. 최신 DB 디스크 스냅샷은 전날 생성된 READY 상태를 확인했지만, 이번에 새 디스크 스냅샷을 만들지는 않았다.

비동기 3회가 끝난 뒤 Patroni 동적 설정을 `synchronous_mode=true`로 바꿨다. 실제 PostgreSQL에서 `synchronous_standby_names`가 **standby 1대**로 설정되고 streaming 2·sync 1·async 1인 것을 확인했다. `synchronous_mode_strict`는 설정되지 않았다. 동기 워밍업과 3회 측정을 마치자 즉시 `synchronous_mode` 키를 제거했다. 복원된 Patroni 설정의 SHA-256이 **시험 전과 바이트 단위로 같았고**, DB는 streaming 2·sync 0·async 2로 돌아왔다.

```text
그림 2 — WAL 커밋 확인 경계

비동기 구간:
  앱 INSERT → primary WAL 커밋 → HTTP 201
                       └──────────▶ standby 2대에 나중에 전송 가능
  Patroni synchronous_mode 키 없음; synchronous_standby_names 비어 있음

동기 구간:
  앱 INSERT → primary WAL 커밋 ───▶ 지정 standby의 동기 확인
                                       │
                                       └─ 확인 후 COMMIT 완료 → HTTP 201
  Patroni synchronous_mode=true; streaming 2대 중 sync 1대·async 1대

주의: 이 그림은 확인된 설정의 정상 흐름이다. 장애 때의 성공 응답
보존이나 standby 상실 시 쓰기 지속 여부는 이번에 시험하지 않았다.
```

## 실행별 결과

| 모드·실행 ID | HTTP 201·DB 고유 행 | 실패 / 결과 불명 / 미전송 | p50 / p95 / p99 (ms) | 부하 VM CPU 평균·최대 | 원장·DB 판정 |
| --- | ---: | ---: | ---: | ---: | --- |
| 비동기 [sync431a01](evidence/issue-431/sync431a01/실행-결과.md) | 1,800 / 1,800 | 0 / 0 / 0 | 12.77 / 53.87 / 57.30 | 3.0% / 9.1% | 통과 |
| 비동기 [sync431a02](evidence/issue-431/sync431a02/실행-결과.md) | 1,800 / 1,800 | 0 / 0 / 0 | 13.26 / 52.79 / 55.10 | 3.1% / 8.5% | 통과 |
| 비동기 [sync431a03](evidence/issue-431/sync431a03/실행-결과.md) | 1,801 / 1,801 | 0 / 0 / 0 | 12.62 / 51.86 / 53.98 | 3.1% / 8.5% | 통과 |
| 동기 [sync431s01](evidence/issue-431/sync431s01/실행-결과.md) | 1,801 / 1,801 | 0 / 0 / 0 | 14.80 / 54.18 / 56.51 | 3.1% / 9.1% | 통과 |
| 동기 [sync431s02](evidence/issue-431/sync431s02/실행-결과.md) | 1,801 / 1,801 | 0 / 0 / 0 | 14.07 / 56.65 / 59.85 | 3.2% / 9.0% | 통과 |
| 동기 [sync431s03](evidence/issue-431/sync431s03/실행-결과.md) | 1,801 / 1,801 | 0 / 0 / 0 | 14.16 / 57.33 / 59.50 | 3.3% / 9.5% | 통과 |

**세 실행의 p95를 단순 평균한 설명용 수치**는 비동기 52.84ms, 동기 56.05ms로 동기 쪽이 3.21ms(약 6.1%) 높다. 이는 합쳐진 5,401/5,403개 요청의 p95가 아니다. p50 실행 평균은 12.89→14.34ms, p99 실행 평균은 55.46→58.62ms였다. 고정 도착률에서 두 모드 모두 약 30 req/s를 소화했고 부하 VM CPU 포화는 관측되지 않았다. **3회씩 순차 측정만으로 인과 효과나 최대 처리량을 단정하지 않는다.** 시간대에 따른 외부 요인, 앱·DB CPU 미계측, 캐시 상태 변화가 남는다.

```text
그림 3 — HTTP 201과 최종 DB 결과의 대조

k6 안전 이벤트 파일: sent(request_id) → ack(201, request_id)
                                            │
                                            ▼
DB primary의 ha_probe_writes를 run_id로 필터
  ├─ request_id당 1행: 이번 실행의 보존 관측
  ├─ 0행: 201 후 누락
  └─ 2행 이상: 중복

비동기: 1,800 + 1,800 + 1,801 = 5,401 ID → 각각 1행
동기:   1,801 + 1,801 + 1,801 = 5,403 ID → 각각 1행
합계 10,804 ID; 누락 0·중복 0·고아 DB 행 0
```

## 명령·위치·결과 해석

| 위치 | 실행한 명령 또는 절차 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| DB VM 컨테이너 | `patronictl list --format json`; `etcdctl endpoint health --cluster` | leader 1·replica 2, etcd 3/3 | 모드 변경 전 안전 관문 통과 |
| DB VM PostgreSQL | `SHOW synchronous_standby_names`; `SELECT ... FROM pg_stat_replication` | 변경 전 sync 0/async 2, 변경 중 sync 1/async 1, 복원 후 sync 0/async 2 | 이름만 바꾼 것이 아니라 실제 DB 복제 상태 변경 확인 |
| DB VM | `patronictl edit-config -q --set synchronous_mode=true --force` | 설정 변경 성공 | 비교할 동기 모드 진입 |
| GCP 내부 부하 VM | `bash run_ha_probe_k6.sh <run_id> 30 60s <내부 LB URL> <보호된 JWT 파일> baseline-write 80` | 각 1,800~1,801건, 실패·미전송 0 | 같은 코드·도착률로 실행별 원장 수집 |
| DB VM → 로컬 오프라인 | 요청 ID별 `COPY (... GROUP BY request_id) TO STDOUT WITH CSV HEADER` → `import_completed_ha_k6.py` → `ha_evidence.py verify` | 여섯 원장 complete, 각 `normal_baseline_pass=true`, 누락·중복 0 | 201과 DB 최종 행을 실행별 대조; 오프라인 가져오기는 HTTP 요청을 새로 보내지 않음 |
| DB VM | `patronictl edit-config -q --set synchronous_mode=null --force`; 설정 SHA-256·DB 복제 재조회 | 원래 SHA-256 완전 일치, async 2 | 시험 설정 원복 확인 |
| GCP 관리 API·DB 노드 | LB health, Patroni/etcd health, 합성 계정·SSH 키·자동 타이머 재확인 | LB 2/2, leader 1·streaming 2, etcd 3/3, 계정 0·임시 키 0·타이머 비활성 | 실행 환경 정상 복구. 시험용 VM은 후속 실험을 위해 유지 |

원본은 실행별 [evidence/issue-431](evidence/issue-431)에 분리했다. 각 실행에는 한국어 `실행-기록.txt`·`실행-결과.md`, 원본 시각의 압축 이벤트·요청 원장·요청 ID별 DB CSV, k6/1초 계측 요약과 manifest가 있다. 공개 전 원장 import의 허용 필드 검증과 내부 IP·프로젝트·계정·JWT 패턴 스캔을 통과했다.

## 실패한 준비 시도와 범위

- 첫 smoke 호출은 runner 파일에 실행 비트가 없어 **exit 126**으로 요청 전 종료됐다. 동일 파일을 `bash`로 호출한 별도 smoke `sync431smk01`은 1 req/s × 5초, 6건 성공·미전송 0건이었다. 실패 시도는 측정 집합에 넣지 않았다.
- 준비 단계에서 설치된 `etcdctl snapshot status`가 지원되지 않았다. 공식 `etcdutl`로 교체했으며, 격리 복원의 첫 호출은 peer URL 옵션 불일치로 실패했다. 옵션을 맞춘 재호출만 성공했다. 이 두 실패는 운영 etcd 멤버를 변경하지 않았다.
- 비동기·동기 워밍업은 각각 30초, 901건씩 실패·미전송 0건이었다. 워밍업과 smoke의 원본은 GCP 부하 VM의 별도 실행 디렉터리에 남지만, 이 PR의 **측정 비교에는 포함하지 않았다**.
- **미검증:** 동기 standby 상실, leader 장애, 승격 시 RPO, 최대 처리량, DB 노드 CPU·WAL flush 시간, 장시간 soak, 여러 부하 수준에서의 반복. 비동기 201 누락 0건은 이 정상 시험의 관측이지 비동기 복제의 RPO 0 보장이 아니다.
- 백엔드 A/B의 실행 JAR 바이트 해시는 일치하지만, **그 바이너리를 만든 정확한 소스 커밋은 이번 시험에서 확인하지 못했다.** 위 `d55512...`는 원장·스크립트를 가져온 저장소 기준 커밋이며 JAR의 빌드 커밋이라고 주장하지 않는다.
