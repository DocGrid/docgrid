# DR-BARMAN-REPLICATION-20261016-R3 — Standby 복제 접속 실패 분류

- 기록 날짜: 2026-10-16 KST. 시작·종료 시각은 수집하지 않았다.
- 목적·위치: GCP 백업 VM에서 DB 3노드의 복제 프로토콜 접근 실패 유형 확인.
- 방법: 기존 Barman 인증을 메모리에서만 사용해 각 노드에 `IDENTIFY_SYSTEM`을 요청하고 오류 원문을 저장하지 않고 범주화했다.
- 통과 기준: Primary·Standby별 성공 수와 실패 범주를 기록하고 임시 OS Login 키를 제거.
- 관측: Primary 1/1 성공, Standby 0/2 성공. 두 Standby는 `pg_hba_rejected`로 분류했다. 임시 키 제거 명령 성공.
- 판정: **실패 원인 확인**. SQL 접속 권한과 복제 접속 권한이 달랐다.
