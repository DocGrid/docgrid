# DR-BARMAN-SLOT-TOPOLOGY-20261016-R4 — 영구 슬롯 등록 전 용량

- 시작·종료: 2026-10-16 22:03:25~22:03:35 KST.
- 목적·위치: GCP 백업 VM에서 세 DB 노드의 물리 슬롯 최대 개수와 보존 한도 재확인.
- 방법: 기존 Barman SQL 인증으로 `max_replication_slots`, `max_slot_wal_keep_size`, 물리 슬롯 수를 읽기 전용 조회. 슬롯 이름·내부 주소 미기록.
- 통과 기준: 3/3 조회, 각 노드 슬롯 상한이 현재 사용량보다 큼, 4GB 유지, 임시 OS Login 키 제거.
- 관측: 최대 슬롯 수 10개/노드, 현재 슬롯 수 Standby·Primary·Standby 2·3·2개, 보존 한도 모두 4GB. Primary Barman 슬롯 활성. 임시 키 제거 명령 성공.
- 판정: **사전 조건 통과**.
