# 동기 모드 OpenSQL Primary 네트워크 분할 시험 — 2026-10-17

## 결론

**최초 HTTP `201`을 받은 쓰기의 DB 보존 기준은 통과했다.** `synchronous_mode=on`, `synchronous_mode_strict=true`로 설정한 격리 OpenSQL 3노드에서, 살아 있는 옛 Primary와 다른 DB 두 노드의 통신을 끊었다. 앱에서 옛 Primary의 OpenProxy TCP 연결은 유지했다. 부하 751건 중 `201` 586건을 받았고, 승격 후 Primary의 DB에서 **586건 모두 확인했다. 누락 0건, 중복 0건**이다. 결과 불명 7건 중 2건도 DB에 반영되어 있었다.

가용성은 별도 판정이다. 분할 중 `500` 77건, `503` 81건, 결과 불명 7건이 발생했다. 이 시험은 **무중단 쓰기 성공이나 모든 순간의 split-brain 부재를 증명하지 않는다.** 분할을 해제한 뒤 별도 15초 확인 회차에서는 실제 76건이 전부 `201`이고 DB에도 각각 한 번 기록됐다.

비동기 구성의 [앞선 분할 시험](../opensql-primary-network-partition-20261017/README.md)은 `201` 632건 중 40건이 승격 DB에서 누락됐다. 이번 회차와 부하 속도·기간은 같지만 시작 Primary, OpenProxy 역할 어댑터, 클러스터 상태 등이 완전히 동일한 통제 실험은 아니므로, 두 회차의 차이를 동기 모드 하나의 효과로 단정하지 않는다.

```text
그림 1 — 이번 회차의 실제 경로와 분할 경계

부하 VM ── HTTP POST + 합성 요청 ID ──▶ 내부 LB ──▶ 앱 A/B
                                                   │
                                                   ▼
                                     OpenProxy A/B (역할 어댑터 경유)
                                                   │
                                                   ├── 옛 Primary DB3: 앱 TCP 접속 유지
                                                   │      ╳ DB1·DB2의 etcd/Patroni/DB 연결
                                                   │
                                                   └── DB1 ↔ DB2: 다수 측 연결 유지
                                                        DB1 승격, 동기 standby 1개

별도 제어 경로: DB 3대 역할을 1초 간격으로 관측하고
             분할 규칙에 독립적인 180초 자동 원복 타이머 설정
```

원본 OpenProxy는 `sync_standby` 역할을 직접 해석하지 못한다. 따라서 이번 결과는 [시험용 역할 어댑터](../patroni-role-compatibility-20261017/README.md)를 거친 OpenProxy 경로이며, 공급사 바이너리의 원본 동기 모드 지원 결과가 아니다. 분할 스크립트는 `sync_standby`를 정상 peer로 인식하도록 조정했다.

## 실행 조건과 판정 방법

| 항목 | 실제 실행·확인 |
| --- | --- |
| 위치 | 새 GCP 격리 환경의 DB 3VM, 앱 2VM, 내부 LB, 부하 VM |
| 모드 | Patroni `synchronous_mode=on`, `synchronous_mode_strict=true`; 시작 전 Primary 1, sync standby 1, async replica 1 |
| Proxy | DB2·DB3 OpenProxy가 시험용 역할 어댑터를 조회. 명시적 read-write 트랜잭션으로 쓰기 경로 확인 |
| 사전 통신 | 각 DB에서 두 peer의 etcd·Patroni·DB TCP 연결 2/2. 빈 분할 규칙의 자동 원복 smoke 통과 |
| 부하 | 내부 LB의 합성 쓰기 API, 5 req/s × 150초, 최초 HTTP 응답만 기록. HTTP 클라이언트 제한 시간 10초 |
| 차단 | 시험 시점 Primary인 DB3와 DB1·DB2 사이 노드 간 연결을 양방향 차단. DB3에서 각 peer 연결 0/2, DB1·DB2는 서로 1/2. 앱 A/B에서 DB3 Proxy TCP 연결 유지 |
| 성공 기준 | 전송 누락 0, 최초 `201` 요청 ID의 승격 DB 누락·중복 0, 분할 규칙 제거와 3노드 재합류. 다른 응답·결과 불명은 별도 집계 |

원시 URL·토큰·내부 주소·프로젝트 ID는 증거 파일에 저장하지 않았다. HTTP 원장에는 합성 요청 ID, 시각, 결과 종류, 숫자 상태만 있고 DB 원장에는 합성 ID와 행 수만 있다. 역할 관측 CSV는 안전한 DB1~DB3 라벨과 역할·상태만 담는다. 파일을 가져오기 전에 필드·값 allowlist를 검사했다.

```text
그림 2 — 최초 응답과 최종 DB 원장 대조

각 HTTP 요청 → 합성 request_id를 외부 부하 VM에 먼저 기록
            → 최초 결과를 201 / 실패 HTTP / 결과 불명으로 확정
            → 분할과 승격이 끝난 뒤 새 Primary의 DB를 요청 ID별 조회

201 586건 ───────────▶ DB 586건 존재, 누락 0
500·503 158건 ───────▶ DB 반영 0
결과 불명 7건 ─────────▶ DB 반영 2, 미반영 5

DB 전체: 고유 ID 588건, 총 588행, 중복 ID 0
재전송으로 결과 불명을 사후 성공처럼 바꾸지 않았다.
```

## 회차별 결과

| 실행 ID·목적 | 실제 요청 | 최초 결과 | DB 감사 | p95 | 판정·증거 |
| --- | ---: | --- | --- | ---: | --- |
| `syncbase01` 정상 기준선 | 75 | `201` 75, dropped 0 | 75행, 누락·중복 0 | 64.499 ms | PASS · [로그](syncbase01.log), [HTTP](syncbase01-http-events.jsonl), [DB](syncbase01-db-audit-no-header.csv), [감사](syncbase01-audit.json) |
| `syncpart01` Primary 분할 | 751 | `201` 586, `500` 77, `503` 81, 결과 불명 7, dropped 0 | 588행, `201` 누락·중복 0 | 5,013.173 ms | **보존 기준 PASS, 무중단 아님** · [로그](syncpart01.log), [HTTP](syncpart01-http-events.jsonl), [DB](syncpart01-db-audit-no-header.csv), [감사](syncpart01-audit.json) |
| `syncpost01` 분할 해제 후 쓰기 | 76 | `201` 76, dropped 0 | 76행, 누락·중복 0 | 63.264 ms | PASS · [로그](syncpost01.log), [HTTP](syncpost01-http-events.jsonl), [DB](syncpost01-db-audit-no-header.csv), [감사](syncpost01-audit.json) |

계획상 기준선과 복구 확인은 5 req/s × 15초였지만, 마지막 회차는 k6의 경계 시각에 실제 **76건**을 실행했다. 계획 수 75로 고쳐 쓰지 않고 원장과 DB의 실제 수를 판정에 사용했다. k6 종료 코드 0은 부하 도구가 종료됐다는 뜻이며, DB 보존의 합격은 별도 감사가 판정했다.

```text
그림 3 — UTC 시간축과 역할 관측의 해석

13:40:55.103  HTTP 부하 시작
13:41:37.993  DB3↔DB1·DB2 차단 규칙 적용
13:41:43.463  첫 HTTP 실패 관측
13:41:54.712  DB3의 마지막 primary/running 1초 표본
13:42:04.157  DB1의 첫 primary/running 1초 표본
13:42:10.649  마지막 HTTP 실패 관측; 이후 201 응답 재개
13:42:36.753  DB3의 마지막 primary/other 표본
13:43:53.894  분할 규칙 수동 해제
13:44 이후    1 Primary·2 streaming Replica, 별도 회복 쓰기 76/76

DB3가 primary/other였던 구간의 SQL 쓰기 가능 여부는 역할 API만으로 모른다.
역할 표본 사이의 모든 순간도 관측한 것은 아니다.
```

DB 역할 원장은 [DB1](syncpart01-db1-observer.csv), [DB2](syncpart01-db2-observer.csv), [DB3](syncpart01-db3-observer.csv)의 1초 표본 각 220개다. DB3의 `primary/other`는 PostgreSQL 상태를 `running`으로 확인한 값이 아니므로 DB1의 Primary 표본과 시각이 겹친다는 이유만으로 동시 쓰기 가능을 단정하지 않는다. 반대로 split-brain이 없었다고 보증하지도 않는다. 이 시험의 확정 증거는 최초 `201` 요청별 최종 DB 대조다.

## 환경과 정리 범위

사용자의 재시험 요청에 따라 **VM·디스크·데이터·시험 도구·동기 strict 구성·역할 어댑터·OpenProxy 시험 설정·시험용 방화벽 규칙은 유지했다.** 동기/Proxy 설정의 자동 원복 타이머는 중지해 다음 실험까지 설정이 남도록 했다. 과금되는 VM은 계속 실행 중이다.

장애 주입 규칙은 해제했고 guard가 inactive를 확인했다. 시험용 단기 관리자 계정과 앱·부하 VM·로컬의 JWT 파일을 제거했다. 이번 시험용 OS Login SSH 키도 계정과 로컬에서 제거했으며, DB VM 3대의 임시 OS 관리자 IAM 바인딩은 각 0건이다. 최종 확인에서 전체 VM 7/7이 RUNNING이고 내부 LB 백엔드가 HEALTHY 2/2였다. OpenSQL은 Primary 1, 동기 standby 1, 비동기 replica 1 및 streaming 2였다. [환경 확인 기록](environment-final-01.log)을 남겼다. 재시험 시에는 새 합성 계정·JWT를 발급해야 한다.

## 준비 중 확인한 사항과 한계

- [사전 점검 기록](preflight-01.log): 앱 DB 계정에서는 `pg_stat_replication.sync_state` 세부 값을 볼 수 없어 Patroni 역할 및 PostgreSQL 동기 설정으로 사전 상태를 확인했다.
- [분할 guard 단위 시험](guard-sync-unit-01.log): `sync_standby`를 peer로 허용하도록 수정했고 로컬 테스트 **5개가 통과**했다.
- 원본 OpenProxy가 동기 역할을 거부해 역할 어댑터가 필요했다. 이 경로를 공급사 원본만의 결과로 발표해서는 안 된다.
- 실제 분할 중 Proxy가 각 순간 어느 DB에 요청을 보냈는지의 연속 추적과, 옛 Primary·새 Primary의 SQL 쓰기 가능 여부에 대한 고빈도 동시 관측은 수행하지 않았다.
- 클라우드 앱의 배포 커밋을 이번 회차에 다시 확인하지 않았다. 결과는 관측한 격리 환경의 실행으로 한정한다.
- 비동기 이전 회차와 동기 이번 회차는 각 1회다. 효과 크기의 반복성·신뢰구간은 검증하지 않았다.

이번 결과의 정확한 발표 문장은 **“동기 strict 구성의 한 분할 회차에서 최초 `201` 586건의 승격 DB 보존을 확인했고, 장애 중에는 실패 158건과 결과 불명 7건이 있었다”**이다.
