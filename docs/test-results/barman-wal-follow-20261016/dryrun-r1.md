# DR-BARMAN-DRYRUN-20261016-R1 — 백업 VM 읽기 전용 실행

- 시작: 2026-10-16 21:04:52 KST
- 위치: GCP 백업 VM, barman 계정
- 방법: 작성 중인 감시기의 follow(..., dry_run=True)로 3개 역할 지표와 Barman 현재 설정 읽기
- 통과 기준: 3개 지표 응답과 읽기 전용 판정, Barman/DB 변경 없음, 임시 SSH 키 제거
- 종료: 2026-10-16 21:05:04 KST
- 관측: {"run_id": "DR-BARMAN-DRYRUN-20261016-R1", "ssh_exit": 0, "temporary_key_removed": true, "worker": {"result": {"status": "invalid_output"}, "worker_exit": 1}}
- 판정: 실패
