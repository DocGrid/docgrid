# DR-BARMAN-SLOT-TOPOLOGY-20261016-R7 — 영구 슬롯 실제 생성

- 시각: 2026-10-16 22:06:58~22:07:09 KST.
- 목적·위치: GCP 백업 VM에서 DB 세 노드 슬롯과 WAL 보존 한도 확인.
- 방법: 각 노드의 `pg_replication_slots`, 설정값을 읽기 전용 조회.
- 통과 기준: Barman 물리 슬롯 3/3 존재, 현재 Primary에서 활성, 한도 4GB 3/3, 슬롯 최대치 이내.
- 관측: 슬롯 3/3 존재, Primary에서만 활성, 각 노드 물리 슬롯 3개, `max_replication_slots=10` 및 보존 한도 4GB. 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**.
