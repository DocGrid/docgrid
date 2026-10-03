# #401 수정 후 양방향 시험 A: node1→node2

- 실행 ID: `issue-401-fixed-switchover-a-01`
- 목적: `--init` 적용 후 이전 leader가 수동 컨테이너 재시작 없이 streaming replica로 재합류하는지 확인한다.
- 실행 위치: GCP DB VM 3대, 독립 systemd 정리 타이머 3개.
- 절차: 세 node runtime/fixture 스크립트 해시·Patroni·etcd·stale-count 관문 → 각 VM 정리 타이머 arm → 현재 primary에 합성 계정 4명 create → 계획 switchover → 이전 leader 자동 streaming 확인 → 새 primary에서 계정 4명 remove → 세 VM guard-status 0명 확인 후 timer cancel.
- 성공 기준: leader 1·streaming replica 2, etcd 3/3, 이전 leader 수동 재시작 **0회**, 합성 계정 0명, 타이머 잔여 0/3. 실패 시 추가 switchover를 중단하고 타이머를 유지한다.
- 한계: HTTP 쓰기 원장과 부하가 없어 RTO·RPO를 여기서 판정하지 않는다.
- 결과: 세 VM의 runtime 계약·정리 스크립트 SHA-256 **3/3 일치**, 이전 합성 계정 **0명**·타이머 **0/3**. node1 leader·node2 후보 lag **0 MB**, `nofailover=false`, etcd **3/3**. 타이머 arm **3/3**, 합성 계정 생성 **4명** 및 세 VM에 복제 **4/4/4명** 확인.
- 계획 이전: 명령 **4초**, 이전 leader(node1)가 **수동 재시작 0회**로 streaming replica에 재합류. 명령 시작부터 전체 `leader 1·streaming replica 2`를 관측한 시간 **19초**(5초 주기 확인·SSH 내부 실행 포함; HTTP 쓰기 RTO가 아님). etcd **3/3**.
- 정리: 새 primary에서 합성 계정 **4명 삭제**. 세 VM에서 `armed=true fixture_count=0` 확인 뒤 timer 취소 **3/3**, 최종 `armed=false fixture_count=0` **3/3**.
- 판정: 이 방향의 자동 재합류·합성 데이터 정리 **PASS**. 반대 방향과 HTTP RTO/RPO는 별도 판정.
