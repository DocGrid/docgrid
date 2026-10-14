# DR-CAPACITY-20261015-R3

- 목적: 별도 백업 수신자를 추가할 수 있는 복제 연결 한도와 백업 저장 용량의 기준선 확인.
- 위치·방법: 로컬 GCP CLI에서 30분 만료 OS Login 키로 운영 OpenSQL 3노드의 읽기 전용 SQL 실행. `pg_is_in_recovery()`, `max_replication_slots`, `max_wal_senders`, `full_page_writes`, `wal_level`, 슬롯 수, `pg_database_size('docgrid')`를 조회.
- 기준: Primary 1·Standby 2가 모두 응답하고, 현재 슬롯 수보다 한도가 크며, 키를 즉시 제거.
- 시각: 2026-10-15 02:12 KST에 사후 기록. 개별 시작·종료 시각은 수집하지 못함.
- 관측: SQL 응답 3/3. 역할 Primary 1·Standby 2. 노드별 슬롯 한도 10개·현재 슬롯 2개, WAL 송신 한도 10개, `full_page_writes=on`, `wal_level=replica`. `docgrid` DB는 각 노드 약 82 MB(원시 바이트 85,915,315~85,964,467).
- 판정: PASS(설정 여유의 읽기 전용 확인). **추가 수신자가 실제로 연결·보관·복원되는지는 이 회차에서 검증하지 않음.** 슬롯이 수신자 중단 시 WAL을 무제한 보존하지 않도록 별도 운영 가드가 필요함.
- 정리: 임시 OS Login 키와 로컬 키 파일 제거. 후속 OS Login 조회에서 30분 만료 활성 키 0개. 운영 데이터·설정 변경 없음.
