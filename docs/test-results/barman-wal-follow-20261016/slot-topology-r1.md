# DR-BARMAN-SLOT-TOPOLOGY-20261016-R1 — Barman 물리 슬롯의 노드별 상태

- 시작·종료: 2026-10-16 21:56:35~21:56:46 KST.
- 목적·위치: GCP 백업 VM에서 DB 3노드의 기존 Barman 물리 슬롯 배치 확인.
- 방법: 현재 Barman 설정의 슬롯 이름과 SQL 접속 설정을 메모리에서만 읽고 각 노드의 `pg_replication_slots`를 읽기 전용 조회. 내부 주소·슬롯 이름·WAL 위치·인증 정보 미출력.
- 통과 기준: SQL 접속 3/3, Primary 1·Standby 2의 슬롯 존재·활성 여부 확인, 임시 OS Login 키 제거.
- 관측: 접속 3/3. 현재 Primary에는 Barman 슬롯 1개가 존재하며 활성. Standby 두 대에는 해당 슬롯이 없다. 전체 물리 슬롯 수는 Primary 3개, Standby 각 2개. 임시 키 제거 명령 성공.
- 판정: **조회 통과, 리더 변경 관문 미통과**. 새 Primary 후보에 Barman 슬롯이 없으므로 현재 상태에서 WAL 수신 전환의 연속성은 입증되지 않았다.
