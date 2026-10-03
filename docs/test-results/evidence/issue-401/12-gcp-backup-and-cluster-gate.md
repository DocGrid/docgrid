# #401 DB 노드 교체 전 백업·클러스터 관문

- 실행 ID: `issue-401-backup-gate-01`
- 목적: 컨테이너 교체 전에 단일 primary·정상 복제·etcd 정족수와 최신 외부 복구 지점을 확인한다.
- 실행 위치: GCP DB VM 3대와 GCP Compute Engine snapshot API.
- 절차: Patroni 역할/복제 및 etcd health 읽기 → node1 영속 볼륨에 root-only 온라인 etcd snapshot 생성 → DB VM 부착 디스크 3개의 신규 snapshot 생성·READY 확인.
- 성공 기준: leader 1·streaming replica 2, etcd health 3/3, root-only etcd snapshot 1개, VM 디스크 snapshot READY 3/3. 하나라도 실패하면 운영 DB 컨테이너 교체 중단.
- 보안: 주소·프로젝트/디스크 ID·etcd key/value·snapshot 원문·비밀은 수집 로그에 기록하지 않는다.
- 중간 결과: Patroni `Leader:running=1`, `Replica:streaming=2`; etcd endpoint healthy **3/3**. 신규 etcd 온라인 snapshot **1,536,032 B**, mode **0600**.
- 디스크 snapshot: 세 VM 각각 신규 snapshot 생성 요청을 확인했다. 첫 명령은 도구 대기 시간이 끝나 종료 코드를 받지 못했으나, snapshot 리소스가 실제 존재함을 조회해 확인했다. 최종 상태 **READY 3/3**, 각 부착 디스크 용량 **30 GiB**. 이름·프로젝트 ID는 공개 로그에서 생략했다.
- 판정: 클러스터·백업 관문 통과. 디스크 snapshot은 실행 중인 VM의 복구 지점이며 전체 DB의 논리적 일관성이나 완전한 restore 시험을 증명하지는 않는다. 스냅샷은 사용자가 별도로 삭제할 때까지 저장 비용이 발생할 수 있다.
