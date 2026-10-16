# DR-BARMAN-SLOT-TOPOLOGY-20261016-R6 — 첫 영구 슬롯 등록 후 재조회

- 시각: 2026-10-16 22:05:49~22:05:58 KST.
- 목적·위치: GCP 백업 VM에서 DB 3노드의 실제 물리 슬롯을 읽기 전용 확인.
- 방법: 각 노드의 `pg_replication_slots`, `max_slot_wal_keep_size` 조회.
- 통과 기준: Barman 슬롯 3/3 생성, Primary에서 활성, 4GB 한도 3/3.
- 관측: Primary 슬롯은 활성이나 Standby 2대에 슬롯이 여전히 없었다. 한도는 3/3에서 4GB. 임시 OS Login 키 제거 명령 성공.
- 판정: **실패**. 첫 PATCH의 슬롯 키가 Patroni가 해석하는 최상위 위치가 아니었다.
