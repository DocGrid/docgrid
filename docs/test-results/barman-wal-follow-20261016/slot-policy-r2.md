# DR-BARMAN-SLOT-POLICY-20261016-R2 — Barman SQL 경로에서 슬롯 상태 재조회

- 기록 시각: 2026-10-16 21:39 KST. 실행 시작·종료의 초 단위 시각은 수집하지 않았다.
- 목적·위치: GCP 백업 VM의 기존 Barman SQL 연결로 현재 Primary의 WAL 보존 설정과 물리 슬롯 수를 읽기 전용으로 조회
- 방법: `current_setting` 3개와 `pg_replication_slots`의 물리 슬롯·활성 물리 슬롯 수를 한 SQL 조회로 읽었다. 접속 문자열·슬롯 이름·주소는 출력하지 않았다.
- 통과 기준: 5개 값 모두 수집, 원문 접속 정보 미기록, 임시 OS Login 키 제거
- 관측: `max_slot_wal_keep_size=4GB`, `wal_keep_size=1GB`, `archive_mode=on`, 물리 슬롯 3개, 활성 물리 슬롯 3개. SQL 조회와 SSH 종료 코드 0. 임시 키 제거 명령 성공.
- 판정: **통과**. 이는 당시 Primary의 설정·슬롯 상태다. Patroni 영구 슬롯 정의는 R1에서 0건이었으므로 새 Primary에서 Barman 슬롯이 유지된다고 추론할 수 없다.
