# #401 두 번째 replica `--init` 교체

- 실행 ID: `issue-401-node3-replica-migration-01`
- 목적: node3도 reaping PID 1로 교체하고 수동 보정 없이 streaming replica로 돌아오는지 확인한다.
- 실행 위치: GCP node3 DB VM. 현재 primary와 첫 replica는 유지한다.
- 사전 조건: leader 1·streaming replica 2, etcd 3/3, READY 디스크 snapshot 3/3, node3 캡처 후보 시험 통과.
- 절차: 사전 관문 → old stop·recoverable rename → 캡처 이미지 create → 기존 bootstrap → 런타임·클러스터 검증. 실패 시 새 컨테이너만 삭제하고 old로 rollback.
- 성공 기준: node3 PID 1 docker-init, leader 1·streaming replica 2, etcd healthy 3/3, 이전 컨테이너 stopped 보존.
- 결과: 사전 클러스터 관문 통과. 기존 node3 컨테이너를 **stopped 상태로 보존**하고 캡처 이미지에서 `--init` 재생성, 기존 bootstrap 성공. 최종 PID 1 reaping-init, Patroni **leader 1·streaming replica 2**, etcd healthy **3/3**, runtime contract 통과. 자동 rollback 실행 **0건**.
- 판정: 두 번째 replica 교체·재합류 **PASS**. 현재 primary node2는 아직 기존 PID 1 `sleep`이므로 전체 수정 완료는 아니다.
