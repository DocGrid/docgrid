# DR-BARMAN-SLOT-TOPOLOGY-20261016-R2 — 슬롯 보존 한도와 지연량

- 시작·종료: 2026-10-16 21:58:56~21:59:06 KST.
- 목적·위치: GCP 백업 VM에서 세 DB 노드의 슬롯 WAL 보존 한도와 현재 Primary의 Barman 슬롯 지연량 확인.
- 방법: Barman 계정의 기존 인증으로 각 노드의 `current_setting('max_slot_wal_keep_size')`, 물리 슬롯 상태, Primary의 `pg_wal_lsn_diff`를 읽기 전용 조회. 내부 주소·슬롯 이름·WAL 위치·인증 값 미기록.
- 통과 기준: 3/3 조회와 역할별 한도·지연량 판정, 임시 OS Login 키 제거.
- 관측: Primary는 4GB, Barman 슬롯 활성, 지연 1,241,608바이트. Standby 두 대는 해당 슬롯 없음, 각각 한도 `-1`(무제한). 임시 키 제거 명령 성공.
- 판정: **조회 통과, 영구 슬롯 적용 관문 미통과**. Standby에도 유한 보존 한도를 적용하고 확인해야 한다.
