# #401 Patroni 이름 보정 후 primary 역할 이전·node2 교체

- 실행 ID: `issue-401-primary-handoff-migration-02`
- 목적: 실제 Patroni 멤버 이름을 런타임에 조회해 node2→node1 계획 이전을 수행하고 old node2를 reaping init으로 교체한다.
- 실행 위치: GCP DB 3노드 및 node2 Docker 호스트.
- 절차: leader·candidate·lag0·etcd3 사전 확인 → `patronictl switchover` → 새 node1 leader 확인 → old node2 보존·캡처 이미지로 재생성 → bootstrap·3노드 복구. 실패 시 즉시 새 컨테이너만 제거하고 old 복구.
- 성공 기준: leader 1·streaming replica 2, etcd 3/3, 세 노드 PID 1 reaping init, old node2 stopped 보존.
- 중간 결과: 사전 node2 leader·node1 후보 streaming(lag **0 MB**)·node3 streaming·etcd **3/3** 통과. 계획 switchover 명령 **5초**, 새 node1 `Leader:running`, node3 `Replica:streaming`. 기존 node2는 `Replica:stopping`, 로컬 PostgreSQL 준비 실패. 컨테이너 내부 좀비 **5개** 중 PostgreSQL 계열 **1개**이고, 최근 Patroni 로그 120줄에 `demote in progress` **12줄**. Docker 호스트의 `docker top`에는 이 좀비가 나타나지 않아 컨테이너 내부 `ps`로 재확인했다.
- 최종 조치: 새 node1 leader·node3 streaming·etcd **3/3**을 다시 확인하고, old node2를 정지·이름 변경해 복구용으로 보존했다. 캡처 이미지로 새 node2를 `--init` 재생성한 뒤 기존 bootstrap 통과. node2 PID 1 reaping-init, 최종 Patroni **leader 1·streaming replica 2**, etcd **3/3**, old node2 stopped. 자동 rollback **0건**.
- 판정: 수정 전 증상 재현과 세 노드의 runtime 교체·복제 복구 **PASS**. 수정 후 계획 switchover에서 이전 leader가 *수동 조치 없이* 돌아오는지는 아직 별도 시험해야 한다.
