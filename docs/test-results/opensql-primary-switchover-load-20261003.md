# OpenSQL primary 계획 이전 중 HTTP 쓰기·요청 ID 대조

- 관련 이슈: [#405](https://github.com/DocGrid/docgrid/issues/405)
- 실행일: 2026-10-03 KST. 원본 이벤트와 Patroni 이력 시각은 UTC다.
- 범위: 시험용 GCP DB VM 3대, OpenProxy 2대, 백엔드 2대, 내부 LB, 공용 캐시 1대, k6 VM 1대. 부하 중 **계획된 switchover**만 실행했다. PostgreSQL 프로세스 종료·리더 VM 상실은 아직 실행하지 않았다.
- 안전 조건: 단일 리더·두 streaming replica, 후보 lag 0 MB·`nofailover=false`, etcd 3/3, READY 부팅 디스크 snapshot, 앱 LB 2/2, 합성 계정의 VM별 자동 정리 타이머 3/3.

```text
그림 1. 실제 요청과 독립 원장

GCP k6 VM -- 고유 request_id / HTTP POST --> 내부 LB
   |                                     |-- 백엔드 A -- Hikari --\
   |                                     `-- 백엔드 B -- Hikari ---+--> OpenProxy A/B
   |                                                                |--> 현재 OpenSQL primary
   +-- sent/201/500/unknown 시각 --> DB 밖 events.jsonl             `--> streaming replica 2대
                                                                       ^
                                             Patroni switchover -----|

시험 종료 후 새 primary에서 request_id별 행 수를 내보내고,
HTTP 결과 원장과 ID 단위로 결합한다. 201·500·미전송 드롭을 섞지 않는다.
```

## 실행·결과

| 실행 ID·목적 | 실행 위치·명령/방법 | 관측 숫자 | 판정·해석 |
| --- | --- | --- | --- |
| `ha405s020` · smoke 첫 시도 | GCP k6 VM, `run_ha_probe_k6.sh` | runner 미배치로 종료 코드 127, HTTP 요청 0 | 실패 시도를 별도 보존. 이후 동일 ID를 재사용하지 않았다. |
| `ha405s020b` · 2 req/s smoke | GCP k6 VM → LB; 10초, DB ID 대조 | 21건 201, 실패·불명·드롭 0; DB 21행, 누락·중복 0; p95 84.57 ms | 경로와 원장 동작 확인. 100 req/s 기준선 아님. |
| `ha405b100a` · 정상 기준선 | GCP k6 VM, 100 req/s × 60초; 새 primary DB 조회 | 6,001건 201, 실패·불명·드롭 0; ID별 1행 6,001개, 누락·중복 0; p95 52.94 ms | 정상 조건 100 req/s 기준선 통과. |
| `ha405sw001` · 첫 계획 이전 | 100 req/s × 90초; Patroni `switchover` | 9,001건 201, DB 누락·중복 0 | **시간 관문 실패:** 실제 승격은 k6 종료 39.6초 후. 부하 중 HA 증거로 사용하지 않는다. |
| `ha405sw002` · 부하 중 계획 이전 | 100 req/s × 180초; 160 최대 VU | 17,620건 전송: 201 17,538·500 82·불명 0. 드롭 381, p99 1,534.38 ms. 새 primary에서 201 누락·중복 0, 500 DB 반영 0 | 실제 승격은 부하 중. 일정 유지 실패로 전체 시험 PASS 아님. |
| `ha405sw003` · 부하 중 계획 이전 반복 | 70 req/s × 180초; 800 최대 VU | 12,434건 전송: 201 12,367·500 67·불명 0. 드롭 167, p99 3,570.66 ms. 새 primary에서 201 누락·중복 0, 500 DB 반영 0 | 실제 승격은 부하 중. 동시 VU 상한을 늘려도 드롭이 남아 일정 유지 실패. |

```text
그림 2. 실제 승격과 부하 창의 관계 (UTC)

ha405sw001  부하 [13:39:56 ────────────── 13:41:26]
             실제 승격                               13:42:05.587  ✗ 부하 뒤

ha405sw002  부하 [13:50:53 ────── 13:52:27.228 ──────── 13:53:54]
                                      승격 /
                              전 201=9,186  뒤 201=8,352  ✓ 실제 겹침

ha405sw003  부하 [14:04:05 ──── 14:05:16.903 ────────── 14:07:05]
                                    승격 /
                              전 201=4,865  뒤 201=7,502  ✓ 실제 겹침

fault start/end 원장 시각은 원격 명령 준비·종료 시각이다.
승격 자체의 시각은 Patroni history의 timeline 전환 기록으로 판정했다.
```

두 실제 겹침 실행 모두 최종 leader 1·streaming replica 2로 자동 복구됐다. `ha405sw002`의 500은 첫 13:52:25.899~마지막 13:52:32.048 UTC, `ha405sw003`의 500은 14:05:15.509~14:05:22.711 UTC에 완료됐다. 첫 실패 전 마지막 201부터 마지막 실패 뒤 첫 201까지 각각 **6.279초**, **7.279초**였다. 이는 *오류 발생 창의 관측 경계*이며, 요청이 중간에 성공할 수 있으므로 연속 서비스 중단 시간이나 일반화된 RTO로 단정하지 않는다. 최대 연속 201 완료 간격은 각각 **6.193초**, **6.217초**였다.

```text
그림 3. 결과를 섞지 않는 판정

목표 스케줄의 각 슬롯
   ├─ 실제 HTTP 전송됨
   │    ├─ 201: sw002 17,538 / sw003 12,367
   │    │       └─ 새 primary에서 ID별 정확히 1행: 누락 0·중복 0
   │    └─ 500: sw002 82 / sw003 67
   │            └─ 새 primary에서 해당 ID 반영 0
   └─ k6 dropped_iterations: sw002 381 / sw003 167
        └─ HTTP 요청 자체가 없어 500·DB 유실 건수에 포함하지 않음

결과 불명/시간 초과로 기록된 요청은 두 실행 모두 0건.
이는 관측 표본의 결과이지 비동기 복제에서 무계획 상실까지 RPO=0을 보장하지 않는다.
```

## 원인 분리와 중단 결정

정상 100 req/s 기준선에서는 드롭이 0이었다. 리더 전환 실행에서는 100 req/s·최대 160 VU에서 381건, 70 req/s·최대 800 VU에서 167건이 스케줄 전에 떨어졌다. 따라서 목표 부하를 실제로 모두 발행했다고 주장할 수 없다. 서버 응답 대기가 VU를 오래 묶었는지, k6 VM CPU·메모리 한계인지, 다른 원인인지 **피크 VU와 시스템 자원 시계열을 수집하지 않아 아직 구분할 수 없다.** 500이 발생했다는 사실과 201 ID 보존은 별개다. 쓰기 요청을 자동 재시도하면 커밋 결과 불명 상황에서 중복을 만들 수 있으므로 이번 단계에서 재시도 정책을 변경하지 않았다.

대조 도구의 `normal_baseline_pass`는 *정상 상태에서 500·불명·드롭이 모두 0이어야* true가 되는 식이다. switchover 두 실행의 `durable_write_pass=false`, 종료 코드 2는 그 식에 500이 포함됐기 때문이며, `acknowledged_missing_count=0`과 모순되지 않는다. 장애별 RTO·RPO 판정은 숫자 필드를 각각 읽어야 한다.

**관문 결정:** 계획 이전 중 HTTP 201의 관측 누락·중복은 0이지만 부하 스케줄이 두 번 실패했다. 부하 발생기 VU·CPU·메모리와 매초 스케줄 드롭을 계측하고 시험 강도를 재확정하기 전까지 PostgreSQL 프로세스 강제 종료와 리더 VM 상실 시험은 진행하지 않는다. VM/서비스는 종료하지 않았고, 각 실행의 합성 계정 4명과 3노드 정리 타이머는 모두 제거·해제했다.

종료 시 etcd endpoint **3/3 healthy**, 내부 앱 LB **2/2 HEALTHY**도 확인했다. 부하 VM의 단기 JWT **5개**, DB 3대와 부하 VM의 임시 SSH 공개키 항목 **4개**, 로컬 임시 개인키·토큰을 모두 제거했다. 자세한 안전 정리는 [`99-recovery-and-access-cleanup.md`](evidence/issue-405/99-recovery-and-access-cleanup.md)에 남겼다.

## 증거·재현 범위

- 실행 소스 지문: 모든 원장의 `manifest.git_sha`는 실험을 시작한 checkout 기준 `44fbfd4`다. 실제 k6 runner는 smoke·100 req/s 기준선에서 이 기본 버전, `ha405sw001/002`에서 SHA-256 `79e226b6...`(후속 커밋 `55a2678`과 동일한 내용), `ha405sw003`에서 SHA-256 `950e7e28...`(후속 커밋 `cf89ec9`와 동일한 내용)을 배포·해시 대조했다. 실행 당시 두 runner 변경은 아직 미커밋 상태였으므로 **manifest의 Git SHA 하나만으로 runner 전체를 재현할 수 없다**. 세 번째 실행 이후 주석만 실제 결과에 맞게 수정했다. 후속 하네스는 실행별 runner·k6 스크립트 SHA를 manifest에 직접 기록해야 한다. 앱 VM의 실행 JAR SHA도 이번 실행에서 재수집하지 않아 manifest가 전체 배포 버전을 증명하지는 않는다.
- 사전 점검: [`evidence/issue-405/00-preflight.md`](evidence/issue-405/00-preflight.md). smoke 당시 동일 내용의 해시 고정본은 [`00-preflight-at-smoke.md`](evidence/issue-405/00-preflight-at-smoke.md)이다.
- 실행별 한국어 로그·manifest·k6 숫자 요약·대조 결과: [`evidence/issue-405/`](evidence/issue-405/). 각 실행 디렉터리는 분리돼 있으며 원본 이벤트·요청 CSV·DB ID 수는 무손실 `.gz`로 보존했다. `gzip -t`와 압축 해제 DB CSV SHA-256 대조는 전 실행 통과했다.
- 실행 명령: GCP 내부 k6 VM에서 `bash run_ha_probe_k6.sh <run_id> <rate> <duration> <내부 LB URL> <0600 토큰 파일> <purpose>`. 로컬에서 `python3 scripts/opensql/ha_evidence.py import-k6|finish|verify`; 당시 primary VM에서 `sudo bash -s -- <컨테이너> <run_id> < scripts/opensql/export_ha_probe_counts.sh`; 로컬에서 `python3 scripts/opensql/reconcile_ha_probe.py --run-dir <실행 디렉터리> --db-csv <DB CSV>`를 실행했다. 공개 증거에서 내부 주소·토큰·GCP 프로젝트 식별자는 제외했다.
- 각 원장은 압축 전 `ha_evidence.py verify`를 통과했다. `reconcile_ha_probe.py`의 정상 기준선 성공은 smoke·100 req/s baseline만 해당한다. 장애 실행은 실패·드롭을 숨기지 않고 별도 판정했다.
