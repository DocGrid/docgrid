# DR-BARMAN-DBACCESS-20261016-R1 — 백업 계정의 세 노드 SQL 접속

- 시작: 2026-10-16 21:08:19 KST
- 위치: GCP 백업 VM의 barman 계정
- 방법: 기존 Barman 연결 설정을 메모리에서 노드별 대상만 바꿔 SELECT pg_is_in_recovery() 실행
- 통과 기준: 3/3 접속, Standby 2·Primary 1, 임시 SSH 키 제거
- 종료: 2026-10-16 21:08:32 KST
- 관측: {"run_id": "DR-BARMAN-DBACCESS-20261016-R1", "ssh_exit": 0, "temporary_key_removed": true, "worker": {"summary": {"rows": [{"connected": false, "standby": null}, {"connected": true, "standby": false}, {"connected": false, "standby": null}]}, "worker_exit": 0}}
- 판정: 실패
