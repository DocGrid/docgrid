# DR-BARMAN-DBACCESS-20261016-R2 — SQL 접속 실패 유형

- 시작: 2026-10-16 21:08:52 KST
- 위치: GCP 백업 VM의 barman 계정
- 방법: 세 노드 읽기 전용 접속 오류의 범주만 분류하고 원문 메시지는 폐기
- 통과 기준: 실패 원인 분류, 접속 문자열·주소·계정 노출 없음, 임시 키 제거
- 종료: 2026-10-16 21:09:04 KST
- 관측: {"run_id": "DR-BARMAN-DBACCESS-20261016-R2", "ssh_exit": 0, "temporary_key_removed": true, "worker": {"summary": {"rows": [{"category": "password_missing", "connected": false}, {"category": "ok", "connected": true}, {"category": "password_missing", "connected": false}]}, "worker_exit": 0}}
- 판정: 원인 분류
