# #401 primary 역할 이전과 node2 컨테이너 교체

- 실행 ID: `issue-401-primary-handoff-migration-01`
- 목적: 수정된 node1로 primary를 계획 이전한 뒤 예전 PID 1의 node2를 안전하게 교체한다.
- 실행 위치: GCP DB VM 3대, `patronictl` 및 node2 Docker 호스트.
- 사전 조건: node1/node3 reaping init·streaming replica, node2 leader, etcd healthy 3/3, 신규 snapshot READY 3/3, node2 캡처 후보 통과.
- 절차: 계획된 switchover node2→node1 → 단일 새 primary 확인 → 예전 node2를 복구 가능한 이름으로 보존 → 캡처 이미지 `--init` 재생성·bootstrap → node2 streaming 재합류. 실패 시 안전한 DB 컨테이너로 복구하고 추가 switchover 중단.
- 성공 기준: 세 노드 모두 reaping PID 1, primary 1·streaming replica 2, etcd 3/3, 이전 node2 컨테이너 stopped 보존. 이 이전은 아직 양방향 회귀 시험으로 계산하지 않는다.
- 첫 시도 결과: switchover 실행 전 Patroni 멤버 이름을 Docker hostname과 같다고 가정한 사전 검사가 종료 코드 **1**로 중단했다. 실제 Patroni는 다른 멤버 명명 규칙을 사용한다. 비식별 역할 집계는 node2 leader, node1·3 streaming(lag 0 MB), etcd **3/3**이다. **역할 이전·컨테이너 교체는 실행하지 않았다.**
- 판정: 사전 게이트가 잘못된 이름을 안전하게 거부했다. 별도 실행 ID에서 실제 Patroni 멤버 이름을 명령 내부에서 조회해 다시 확인한다.
