# OpenSQL 3노드 컨테이너·Patroni 기준선

- 실행 ID: `issue401-gcp-baseline-02`
- 시작: 2026-10-03 18:33 KST
- 목적: 원인 수정 전 세 DB VM의 Docker init·PID 1·Patroni/etcd 상태와 기존 강등 로그를 비식별 수치로 확정한다.
- 위치: 승인된 GCP 계정의 DB VM 3대. 만료형 인스턴스 전용 SSH 키와 IAP를 사용하고 작업 후 키를 제거한다.
- 명령/절차: `docker inspect`의 init·명령·마운트·네트워크 항목, 컨테이너 내부 `ps`의 프로세스 상태, `patronictl list --format json`, `etcdctl endpoint health`, 기존 Patroni 로그의 강등 구간 조회.
- 성공 기준: primary 1·streaming replica 2, etcd 정족수 확인, 세 컨테이너의 PID 1·init 설정과 기존 실패의 종료 단계 확인. 시크릿·주소·프로젝트 ID는 기록하지 않는다.

| 확인 | 관측 결과 | 해석 |
| --- | --- | --- |
| 계정·VM 대상 | 승인된 계정 일치, DB VM `RUNNING` **3/3**, 임시 키 등록 전 인스턴스 SSH 키 **0/3** | 대상 확인 후 VM 전용 만료형 키만 등록. 프로젝트 공통 키는 변경하지 않음 |
| Docker PID 1 | 세 노드 모두 `sleep`, `--init` 미사용, `--network host`, 재시작 정책 `unless-stopped` | 고아 자식을 회수하는 init이 없는 구성 |
| Docker 이미지·자원·마운트 | 동일 Rocky Linux 9.7 이미지, 각 2 vCPU·6 GiB 제한, 데이터 bind mount 1개가 예상 위치와 일치 **3/3**, 추가 환경 변수는 이미지 기본값과 동일 | 현재 컨테이너 생성 인자의 보존 가능성 확인. 비밀 값·호스트 경로는 기록하지 않음 |
| 현존 좀비 프로세스 | node1 **0개**, node2 **4개**(`pkill` 1·`openproxy` 3), node3 **4개**(동일), 모두 PPID 1 | `sleep` PID 1이 종료 자식을 회수하지 못한 별도 실환경 증거 |
| DB·DCS | Patroni leader **1**, streaming replica **2**, timeline **2**; etcd endpoint **3/3 healthy** | 다음 변경 전 정상 기준선 |
| 이전 강등 로그 | 14:17:15 KST 강등 시작, 14:17:16 PostgreSQL shutdown 완료, 이후 14:23:09까지 `demote in progress` 반복. 14:23:17 수동 복구 후 새 postmaster 시작 | PostgreSQL 종료 뒤 Patroni의 강등 완료 단계가 멈춘 구간을 특정. 정확한 대기 함수는 아직 미확정 |

진단 중 첫 `docker inspect --format` 호출은 존재하지 않는 `HostConfig.Init` 맵 키 때문에 종료 코드 1이었다. 원본 값은 변경되지 않았고, JSON을 `jq`로 검사하는 방식으로 다시 실행해 `init=false`를 확인했다.

중간 판정: **실환경 구성·실패 구간 확인**. 로컬 대조 시험에서 `--init`이 좀비를 제거한 것은 별도 기록에 있다. Patroni가 실제로 이 좀비 때문에 기다렸는지는 수정 후 역할 이전 재시험으로 판정해야 한다. 임시 SSH 키는 작업 종료 시 제거한다.
