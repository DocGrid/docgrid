# primary PostgreSQL 프로세스 장애 — `ha448proc01`

- 시간: 2026-10-09 **05:34:00~05:37:01 KST**. postmaster SIGKILL **05:34:19**.
- 위치: GCP 내부 k6, node3의 PostgreSQL 프로세스만 종료, Patroni/etcd/VM 유지, 복구 후 primary DB.
- 목적/성공 기준: 같은 노드 재시작인지 새 리더 선출인지 구분하고, 201/실패/불명 요청을 DB 행과 대조한다. 장애 중 HTTP 실패는 숨기지 않는다.

| 실행 위치·명령/방법 | 결과 요약 | 해석 |
| --- | --- | --- |
| DB 3노드 안전 관문: Patroni `/cluster`, DB health, 프록시 A/B status | leader **1**, streaming replica **2·lag0**, 프록시 **2/2 ready**, fixture 타이머 **3/3 armed** | 장애 전제 통과. 기존 JWT 잔여 시간이 짧아 앱 A에서 **새 20분 단기 토큰**을 생성·암호화 전송했고 중간 파일을 지웠다. 첫 토큰 시도는 VM 계정명 불일치로 중단, 수정 후 성공. |
| 부하 VM: `run_ha_probe_k6.sh ha448proc01 30 180s <내부 LB URL> <보호된 JWT 파일> primary-process-fault 160` | HTTP **5,400**, 201 **5,161**, 500 **230**, 401 **4**, 불명 **5**, dropped **0**, k6 exit **99** | 실패율 **4.4259%**로 정상 임계값 실패. 부하 발생기 드롭은 아니다. |
| 현재 primary VM: `ha_primary_process_fault.sh <primary> ha448proc01` | **05:34:19 KST**에 검증된 postmaster만 SIGKILL | VM·Patroni·etcd는 중단하지 않았다. |
| 부하 VM 이벤트 타임라인 | 첫 비201 **05:34:19.990**, 마지막 비201 **05:34:32.109**; 연속 201 최대 간격 **11.360초** | 약 12.119초의 비201 관측 구간과 성공 간격은 다른 지표다. |
| 관측 VM: Remote Write 최종 합계 대조 | 201 **5,161/5,161**, 500 **230/230**, 기타 실패 **4/4**, 불명 **5/5**, exit **0** | Grafana 결과별 시계열과 원장 최종 합계 일치. 중간 push의 매회 성공은 미검증. |
| 복구 후 primary: 요청 ID별 `export_ha_probe_counts.sh` | DB 고유 ID **5,164**; 201 누락·중복 **0/0**, 실패 234건 DB 반영 **0**, 불명 5건 중 DB 반영 **3** | 결과 불명은 자동 재시도 시 중복 가능성이 있는 핵심 사례다. |
| Patroni `/cluster`, LB health, 프록시·fixture 점검 | 기존 node3가 다시 **leader/running**, replica 2대 streaming/lag0, LB **2/2 HEALTHY** | 이 시험에서는 새 leader 선출이 없었다. |
| 현재 primary fixture 삭제 → 세 DB VM timer cancel → 부하 VM JWT 삭제 | 합성 계정 **4개 삭제**, timer **3/3 absent**, JWT **2개 absent** | 시험 데이터와 접근 토큰의 원복 확인. |

p50/p95/p99는 **12.46/2,715.64/5,011.01 ms**, 활성 VU 최대 **155**다. 원장 SHA-256: `5832e1c6ac799f1788098ee3ae17988cb6d571909b83d891cbc16951468741a3`. HTTP 401 4건은 실제 관측됐지만 원인은 이번 범위에서 미확정이다. 201 누락 0건은 이 비동기 복제 실행의 관측값이지 보장된 RPO 0이 아니다.
