# #401 첫 replica `--init` 교체

- 실행 ID: `issue-401-node1-replica-migration-01`
- 목적: 현재 primary를 건드리지 않고 첫 replica의 PID 1만 reaping init으로 바꿔 서비스 재기동·복제 재합류를 확인한다.
- 실행 위치: GCP node1 DB VM. node2 primary·node3 replica는 그대로 둔다.
- 사전 조건: 신규 디스크 snapshot READY 3/3, etcd 온라인 snapshot, leader 1·streaming replica 2, etcd healthy 3/3, 캡처 이미지 후보 시험 통과.
- 절차: 마지막 읽기 전용 관문 → 기존 컨테이너 stop·복구용 이름으로 rename → 캡처 이미지로 `--init` 새 컨테이너 생성 → 기존 host bootstrap → Docker init/PID 1·Patroni/etcd 확인. 모든 중간 오류에는 새 컨테이너만 삭제하고 보존한 기존 컨테이너를 되돌린다.
- 성공 기준: node1 PID 1 docker-init, Patroni leader 1·streaming replica 2, etcd healthy 3/3, 옛 컨테이너 stopped로 보존, 데이터 마운트 동시 사용자 0.
- 경고: DB replica 하나가 교체 동안 일시 중지된다. 디스크 snapshot은 자동 복구를 대신하지 않는다.
- 결과: 사전 `Leader:running=1`, `Replica:streaming=2`, etcd healthy **3/3** 통과. 기존 node1 컨테이너를 정지·이름 변경해 **stopped 상태로 보존**했다. 캡처 이미지의 새 컨테이너는 `Init=true`, PID 1 reaping-init, 기존 bootstrap 통과. 최종 Patroni **leader 1·streaming replica 2**, etcd **3/3**, 도우미 runtime contract 통과. 자동 rollback은 실행되지 않았다.
- 판정: 첫 replica의 서비스 재기동·복제 재합류 **PASS**. node1이 이전 리더가 되는 역할 이전은 아직 시험하지 않았으므로 원인 해결 판정은 보류한다.
