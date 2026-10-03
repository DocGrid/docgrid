# #401 수정 후 양방향 시험 B: node2→node1

- 실행 ID: `issue-401-fixed-switchover-b-01`
- 목적: 반대 방향에서도 이전 leader가 수동 조치 없이 streaming replica가 되는지 반복 확인한다.
- 실행 위치: GCP DB VM 3대, 각 VM 독립 정리 타이머.
- 절차: A 실행 통과 확인 후 새 run ID로 타이머 arm 3/3·합성 계정 4명 → 계획 switchover → 자동 재합류 → 새 primary에서 계정 제거 → 타이머 3개 취소·최종 3노드 복제 확인.
- 성공 기준: leader 1·streaming replica 2, etcd 3/3, 수동 재시작 0회, 합성 계정·잔여 타이머 모두 0.
- 결과: A 시험 통과 후 세 VM runtime·정리 스크립트 해시 **3/3 일치**, stale 계정 **0명**, 기존 타이머 **0/3**. node2 leader·node1 후보 lag **0 MB**, `nofailover=false`, etcd **3/3**. 새 타이머 arm **3/3**, 합성 계정 **4명**이 세 DB 노드에 복제된 것 확인.
- 계획 이전: 명령 **4초**, 이전 leader(node2)가 수동 재시작 **0회**로 streaming replica에 재합류. 명령 시작부터 전체 `leader 1·streaming replica 2` 관측 **18초**(5초 간격 확인·SSH 내부 실행 포함; HTTP 쓰기 RTO가 아님). etcd **3/3**.
- 정리: 새 primary에서 합성 계정 **4명 삭제**, 세 VM에서 0명 확인 후 timer 취소 **3/3**. 최종 `armed=false fixture_count=0` **3/3**, 세 노드 runtime 계약 **3/3**, 최종 Patroni leader **1**·streaming replica **2**·etcd healthy **3/3**.
- 판정: 반대 방향의 자동 재합류·합성 데이터 정리 **PASS**. 양방향 계획 switchover 두 실행 모두 수동 복구 0회. 무계획 장애·HTTP RTO/RPO는 이번 이슈에서 측정하지 않았다.
