# DR-BARMAN-SLOT-TOPOLOGY-20261016-R3 — 보존 한도 적용 후 실제 DB 값

- 시작·종료: 2026-10-16 22:01:17~22:01:27 KST.
- 목적·위치: GCP 백업 VM에서 DB 3노드가 공유 설정의 슬롯 WAL 보존 한도를 실제로 적용했는지 조회.
- 방법: Barman 계정으로 각 노드의 `current_setting`과 슬롯 상태를 읽기 전용 조회. 비공개 값 미기록.
- 통과 기준: 3/3 접속 및 4GB 적용, 기존 Primary 슬롯 활성, 임시 OS Login 키 제거.
- 관측: Standby·Primary·Standby 모두 4GB. 기존 Primary의 Barman 슬롯은 활성, 지연 1,241,608바이트. 임시 키 제거 명령 성공.
- 판정: **통과**. 새 Primary 후보에도 유한 WAL 보존 한도가 적용됐다.
