# 살아 있는 Primary의 네트워크 분할 시험 — 2026-10-17

## 판정

**데이터 보존 기준 실패.** 새 GCP 격리 환경에서 옛 Primary는 앱에서 계속 접근 가능하게 두고, DB 노드 간 연결만 끊었다. 최초 HTTP `201`을 받은 632개 요청 가운데 **40개 ID가 승격한 Primary의 DB에 없었다.** 원래 계획했던 `201` 보존 100%를 충족하지 못했다. 별도로 클러스터 역할·복제·LB와 시험 자원은 복구했다.

이 결과는 실제로 관측한 한 회차의 **비동기 복제 구성**에 한정한다. Patroni의 역할 조회는 1초 표본이며 모든 순간의 쓰기 가능 여부를 증명하지 않는다. 비동기 복제 중단 후 옛 Primary가 승인한 쓰기가 새 Primary에 전파되지 않았을 가능성이 높지만 WAL·패킷 수준의 원인 추적은 하지 않았다.

```text
그림 1 — 시험 경로와 차단 범위

부하 VM: 합성 요청 ID별 최초 응답 기록
    │ 5 req/s × 150초, 실제 HTTP POST
    ▼
내부 LB ──▶ 앱 A/B ──▶ OpenProxy A/B ──▶ OpenSQL DB2 (시험 시작 시 Primary)
                                          │
                                 앱→옛 Primary 접속 유지
                                          │
                         [DB2의 전용 nft input/output DROP]
                              ╳                   ╳
                       DB1 Replica ◀──연결──▶ DB3 Replica
                            etcd 다수 유지, DB3 승격

별도 제어 경로: VM 접속·1초 역할 표본·180초 자동 원복 타이머
```

차단 중 DB2에서 두 peer의 etcd·Patroni·DB TCP 포트 연결은 각 **0/2**, DB1·DB3에서는 서로의 연결이 각 **1/2**였다. 앱 A/B에서 DB2의 OpenProxy TCP 포트 연결은 유지됐다. 포트 도달성은 쓰기 성공과 별도로 확인했다.

## 사전 조건과 방법

| 항목 | 실제 확인한 조건 |
| --- | --- |
| 실행 위치 | 새 GCP 격리 환경, DB 3VM·앱 2VM·Redis 1VM·부하 1VM |
| DB 제어 | Patroni `ttl=30초`, `loop_wait=10초`, `failsafe_mode=true`, `synchronous_mode=None` |
| 초기 역할 | 계획된 switchover 후 DB2 Primary, DB1·DB3 streaming Replica |
| 데이터 경로 | OpenProxy는 DB2·DB3에 설치. 명시적 `BEGIN READ WRITE` 확인에서 새 Primary로 라우팅 |
| 부하 | 내부 LB의 `/api/ha-probe/writes`, 5 req/s × 150초, 합성 ADMIN JWT와 요청 ID |
| 자동 원복 | 분할 전 빈 nft 테이블로 시스템 타이머 실행·제거 시험 통과; 실제 규칙은 180초 후 자동 제거되도록 예약 |
| 판정 | 요청별 최초 HTTP 응답 원장과 **새 Primary의** `ha_probe_writes` ID별 행 수 대조. 자동 재시도 결과를 최초 응답과 섞지 않음 |

기준선 `partbase01`은 같은 앱 경로에서 **75건 중 `201` 75건, DB 75건, 중복 0건, dropped 0건**이었다. [기준선 로그](partition-baseline-01.log), [요청 원장](partbase01-http-events.jsonl), [k6 요약](partbase01-k6-summary.json)을 분리했다.

```text
그림 2 — 관측 시각, UTC; 표본 간 상태는 미관측

12:45:15  150초 HTTP 요청 원장 시작
12:45:52  DB2의 로컬 etcd health 조회 불가 첫 1초 표본
           ├─ DB2↔DB1/DB3 TCP 단절, DB1↔DB3 유지
           └─ 옛 Primary가 여전히 running인 동안 일부 쓰기에 201 응답
12:45:51~12:46:09  최종 DB에서 누락된 201 응답 40건의 시각
12:46:11  DB2 `primary/running` 마지막 1초 표본
12:46:20  DB3 `primary/running` 첫 1초 표본
12:46:52  DB2 `replica/running` 첫 1초 표본
12:47:34  DB2 로컬 etcd health 복구 첫 1초 표본
12:47:48  부하 종료; 이후 새 Primary의 DB ID별 감사

DB2 상태가 일부 구간 `primary/other`였으므로 그 구간의
실제 SQL 쓰기 가능 여부는 이 역할 표본만으로 판정하지 않는다.
```

## 요청·DB 대조

| 최초 결과 | 요청 ID | 최종 DB에 존재 | 최종 DB에 없음 |
| --- | ---: | ---: | ---: |
| HTTP `201` | 632 | 592 | **40** |
| HTTP `500` | 64 | — | — |
| HTTP `503` | 44 | — | — |
| HTTP `401` | 6 | — | — |
| 결과 불명 | 5 | 1 | 4 |
| 합계 | **751** | **593** | — |

`500`·`503`·`401`로 분류된 114개 요청 ID는 DB에 0건이었다. 전체 DB 기록은 **593개 고유 ID·593행·중복 0건**이다. `201` 누락 40건의 첫 응답 시각은 `12:45:51.779~12:46:09.222 UTC`로, 옛 Primary가 `primary/running`으로 관측된 구간에 집중됐다. 결과 불명 5건 중 1건은 DB에 있으므로 재시도 없이 실패로 단정해서는 안 된다.

부하 도구의 `k6_exit=0`은 전송과 계측의 종료를 뜻할 뿐 **데이터 보존 시험의 합격이 아니다.** dropped iteration은 0건, HTTP p50·p95·p99는 각각 **17.4·5,015.0·5,052.5ms**였다. 분할 해제 후 관측된 `201` 56건은 모두 DB에 있었고 그 구간의 실패·결과 불명은 0건이었다.

```text
그림 3 — 성공 응답과 DB 원장의 불일치

외부 원장: 요청 ID별 최초 결과
  ├─ 201 632건 ─────┬── DB에 592건 존재
  │                 └── DB에 40건 없음 ← 이번 시험 실패
  ├─ 500·503·401 114건 ── DB에 0건 존재
  └─ 결과 불명 5건 ───┬── DB에 1건 존재
                    └── DB에 4건 없음

최종 DB: 고유 ID 593건, 중복 ID 0건
재시도/재확정: 이 실행에는 포함하지 않음
```

[주 시험 로그](partition-primary-01.log), [HTTP 최초 응답 원장](partnet01-http-events.jsonl), [최종 DB ID별 원장](partnet01-db-audit.csv), [DB1 역할 표본](partnet01-db1-observer.csv), [DB2 역할 표본](partnet01-db2-observer.csv), [DB3 역할 표본](partnet01-db3-observer.csv), [k6 요약](partnet01-k6-summary.json)을 각각 보관했다. JSONL에는 합성 요청 ID·시각·응답 종류만 있다. CSV에는 합성 요청 ID·행 수 또는 안전한 노드 라벨·역할·상태만 있다. 복사 전에 필드·값 allowlist를 검사했다.

`scripts/opensql/audit_primary_partition.py`를 같은 HTTP 원장과 DB 원장에 다시 실행해 **751개 요청·201 누락 40개·DB 중복 0개·FAIL**을 재계산했다. [감사 실행 기록](partition-audit-01.log)과 [집계 JSON](partnet01-audit-summary.json)을 원시 증거와 분리했다.

## 준비 중 확인한 사항과 복구

| 항목 | 관측과 조치 |
| --- | --- |
| 자동 원복 준비 | 첫·세 번째 smoke에서 타이머 완료 확인 시점이 너무 짧아 실패했다. 폴링 기한을 늘린 네 번째 smoke가 `automatic_rollback=passed`였다. [개별 기록](partition-smoke-01.log), [두 번째](partition-smoke-02.log), [세 번째](partition-smoke-03.log), [네 번째](partition-smoke-04.log) |
| 역할 이전 준비 | 첫 시도는 Patroni 후보 이름·Replica 상태 전제가 틀려 **요청을 보내지 않고** 중단했다. 실제 `/cluster` 정보를 사용한 두 번째 시도에서 HTTP 200, DB2 Primary와 streaming 2개를 확인했다. [첫 기록](partition-switchover-01.log), [두 번째](partition-switchover-02.log) |
| 프록시 판정 | 일반 `SELECT`는 복제본에 라우팅될 수 있어 `pg_is_in_recovery()=true`가 나왔다. 명시적 read-write 트랜잭션으로 다시 확인해 새 Primary 경로가 정상임을 확인했다. |
| 합성 계정 | DB1에는 OpenProxy 설정이 없어 첫 자동 정리 타이머 설치가 실패했다. OpenProxy가 설치된 DB2·DB3에만 반복 정리 타이머를 두고, 제거는 Proxy의 read-write 경로로 처리했다. 시험 후 계정 0건·두 타이머 모두 비활성을 확인했다. |
| 환경 원복 | 분할 nft 테이블 0개, 새 Primary 1개·streaming Replica 2개, 내부 LB HEALTHY 2/2. 시험 전 생성한 스냅샷 3개, VM별 임시 OS 관리자 바인딩, 임시 SSH 키, 합성 JWT, DB VM 시험 스크립트·원격 관측 파일을 제거했다. 기존 7VM은 시험 환경으로 유지했다. |

현재 구성에서 외부 최초 성공 응답의 보존을 주장하려면 비동기 복제의 손실 창을 줄이거나, 다수와 단절된 옛 Primary의 쓰기 승인 경로를 더 일찍 막는 설계가 필요하다. 이는 이번 시험에서 구현·검증한 개선안이 아니다. 동기 복제·강제 fencing·재전송 API의 효과도 이 실행으로는 확인하지 않았다.
