# GCP 계획 전환 재시험 사전 게이트

- 실행 ID: `ha412-preflight-20261004`
- 목적: 실제 부하 중 planned switchover를 수행해도 판정·정리가 가능한지 확인. 이 로그는 장애 실행 결과가 아니다.
- 위치: 로컬 gcloud 읽기 + GCP DB VM 게스트 내부 Patroni/etcd 읽기 + 내부 LB health API.
- 기준 시각: 첫 설정 지문 [config-safe.json](config-safe.json) 작성 **2026-10-03 16:36:12 UTC / 2026-10-04 01:36:12 KST**. 두 번째·세 번째 전환 전 설정 지문은 [VU280](config-safe-tuned.json), [VU400](config-safe-vu400.json)이다.
- 성공 기준: 대상 계정·프로젝트 확인, DB 3/3·앱 2/2·부하 1/1 실행, Patroni leader 1·streaming replica 2/lag 0, etcd 3/3, 앱 LB 2/2, 현재 DB 디스크 3개 모두 READY snapshot, 승격 후보에 nofailover 금지 없음, 합성 계정 정리 가드 3/3. 하나라도 실패하면 switchover 중단.

| 확인 위치·방법 | 관측 결과 | 판정과 한계 |
| --- | --- | --- |
| 로컬 gcloud 구성·Compute VM 목록 | 승인된 계정/프로젝트, DB VM **3/3 RUNNING**, 앱 VM **2/2 RUNNING**, 부하 VM **1/1 RUNNING**, 공유 캐시 VM **1/1 RUNNING** | 인프라 실행 확인. 이름·IP·프로젝트 ID는 공개 증거에 넣지 않음 |
| Compute snapshot 목록과 부착 디스크 source 정확히 대조 | DB 부착 디스크 **3개 모두 READY snapshot**으로 커버. 점검 당시 최신 생성 후 약 **6시간** | 복구 지점 존재. 온라인 snapshot만으로 다중 노드 일관 복구를 증명하지 않음 |
| 게스트 `patronictl list --format json` | 첫 실행 전 timeline **8**, leader 1·streaming 2·두 후보 lag **0 MB**; 다음 실행 전 timeline 9, 세 번째 전 timeline 10 | 각 장애 전 현재 역할·승격 후보 확인 |
| 게스트 Patroni REST·설치 설정 | 3노드 REST 응답, 승격 금지 tag 없음; `ttl=30`, `loop_wait=10`, `maximum_lag_on_failover=1048576`, `failsafe_mode=true` | 실제 설치 상태. 계획 이전 외 VM 장애 결과를 추정하지 않음 |
| `etcdctl endpoint health --cluster --write-out=json` | 장애 주입 전 반복 조회마다 **3/3 healthy** | 정족수 확인. etcd 장애는 주입하지 않음 |
| 내부 LB backend health | 장애 주입 전 매번 앱 **2/2 HEALTHY** | 앱 접근 경로 확인. HTTP 쓰기는 별도 기준선으로 검증 |
| GCP 부하 VM 스크립트 SHA-256 | runner·k6 JS·계측기 배치본이 각 로컬 검증본과 일치 | 구버전 계측 결과와 혼합하지 않음 |
| 세 DB VM의 `docgrid-permission-fixture` | 이전 시험 합성 계정 **0명**, 실행별 새 계정 **4명** 생성 전에 독립 자동 정리 타이머 **3/3 무장** | 장애 시에도 계정 정리 시도 가능 |

설치 버전은 현재 게스트에서 OpenSQL **v3.17.8.7**, OpenProxy **1.1.3**, Patroni **4.0.5**, etcd **3.6.5**로 다시 읽었다. 기존 [etcd snapshot 격리 복원 기록](../issue-397/09-live-backup-gate.md)은 참고했지만, **이번 실행에서 전체 3멤버 etcd 복원을 다시 리허설하지 않았다.** 따라서 큰 VM 상실이나 정족수 시험의 관문으로 이 결과를 재사용하지 않는다.

처음 만료형 SSH 접속은 예상과 달리 프로젝트 공통 `ssh-keys` metadata에 이번 시험 키 여러 줄을 추가했다. 이 상태를 숨기지 않고 [종료·접근 정리](01-cleanup.md)에서 기존 키를 그대로 보존하며 정확히 제거했다.
