# 리더 이동 중 시험 계정 정리 실환경 실행 로그

- 실행 ID: `6d5b9c7345df`
- 시작: 2026-10-03 14:14:22 KST
- 환경: GCP Rocky Linux 9.7/OpenSQL DB VM 3대, PR #398의 정리 도우미 해시 3/3 일치
- 목적: 세 VM의 독립 타이머를 먼저 무장하고 계정 4명을 생성한 뒤, Patroni의 **계획된 switchover**로 리더를 이동시켜 새 primary에서 이 계정을 안전하게 정리한다.
- 사전 조건: Patroni leader 1·streaming replica 2, 후보 lag 0 MB·동일 timeline·`nofailover=false`, etcd endpoint 3/3 healthy, 별도 READY 디스크 snapshot 및 무결성/격리 복원된 etcd snapshot 보유.
- 실행 방법: `docgrid-permission-fixture arm|guard-status|create|remove|cancel`과 설치된 `patronictl switchover --leader --candidate --force`. 내부 실제 이름·주소·계정 ID는 로그에 쓰지 않는다.
- 성공 기준: 최초 타이머 3/3·계정 4명, switchover 후 단일 primary·두 streaming replica와 계정 4명 유지, **새 primary**의 계정 삭제, 세 VM 계정 0명·타이머 0/3, 원래 리더로 계획 복귀 후 단일 primary·정상 복제·etcd 3/3.
- 실패 처리: primary가 하나로 확인되지 않거나 역할 이동이 불완전하면 추가 switchover를 중단한다. 계정이 남으면 타이머를 유지하고 현재 primary에서만 정리한다.
- 측정 범위: 합성 계정의 생존·정리 확인. HTTP 요청 원장과 부하를 동반한 RTO·RPO 측정은 **별도 시험**이다.

| 시각 (KST) | 사건·명령 | 관측 숫자·상태 | 판정 |
| --- | --- | --- | --- |
| 14:14:22 | 사전 조건 확정 | 후보 node2: streaming, lag 0 MB, leader와 timeline 일치. node2/3 `nofailover=false`, etcd 3/3 healthy | 시작 관문 통과 |
| 14:15경 | 세 타이머 무장 | `arm` 성공 3/3; 세 노드 모두 `armed=true fixture_count=0`; 역할 primary 1·replica 2 | 생성 관문 통과 |
| 14:16:29 | 합성 계정 생성·복제 | 현재 primary의 `create` 성공, 기대 형식 4행. 세 노드 모두 `armed=true fixture_count=4` | 계획 역할 이전 준비 완료 |
| 14:17:15 | `patronictl switchover --leader <기존 리더> --candidate <후보> --force` | 명령 종료 코드 0, 약 10초 후 node2가 새 primary. node3는 streaming replica이고, node2/3의 계정 수는 각각 4명 | 새 primary 선출·계정 보존 관측 |
| 14:17 이후 | 이전 리더 node1 조회 | `patronictl list`에서 node1은 `stopping`; 로컬 `psql`은 connection refused. 당시 배포본의 `role`은 이를 `replica`, `guard-status`는 `fixture_count=` 빈 값으로 **종료 코드 0**에 보고 | **실패 발견**. node1 복제·상태 조회를 정상이라고 간주할 수 없음 |
| 14:18경 | 새 primary의 `remove` | node2에서 계정 4명 삭제 성공, `armed=true fixture_count=0`. node3도 계정 0명 확인 | 계정 정리는 성공. 이전 리더의 DB 조회는 아직 불가 |
| 14:20 이후 | 이전 리더 장애 진단 | Patroni 로그가 `switchover: demote in progress`를 반복. PostgreSQL 종료 로그는 있었으나 재기동되지 않았고 node1은 `stopping` 상태 지속 | 계획된 역할 이전이 자동으로 3노드 정상 상태에 복귀하지 못함. 원인은 미확정 |
| 14:23경 | node1만 `docker restart --time 15` 후 기존 `docgrid-opensql-bootstrap 1` 실행 | etcd·Patroni 재시작, `pg_isready` 정상. node1이 timeline 2의 streaming replica로 재합류. node2 leader·node3 streaming 유지, etcd endpoint 3/3 healthy | 수동 복구 성공. 다른 리더·replica는 재시작하지 않음 |
| 14:24경 | 세 타이머 정리 | 세 노드 모두 계정 0명 확인 뒤 `cancel` 성공 3/3. 최종 `armed=false fixture_count=0` 3/3 | 합성 계정 0명·잔여 타이머 0/3 |

최종 판정: **부분 성공 / 사전 정의한 전체 성공 기준은 미달**. 새 primary에서 합성 계정 삭제와 3노드 타이머 정리는 통과했다. 그러나 기존 리더 node1이 자동으로 replica에 재합류하지 않아 수동 컨테이너 재시작·bootstrap이 필요했고, 안전상 원래 리더로 재전환하지 않았다. 이는 HA 복구 경로의 실제 실패 사례다. 당시 가드의 DB 조회 실패 오판은 [별도 로컬 회귀](11-local-query-failure-regression.md)로 수정했다. HTTP 요청·쓰기를 발생시키지 않았으므로 RTO·RPO, 성공 응답 데이터의 내구성은 여기서 판정할 수 없다.
