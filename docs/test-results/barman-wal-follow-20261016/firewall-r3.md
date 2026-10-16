# DR-BARMAN-FIREWALL-20261016-R3 — 우선순위 수정 후 재조회

- 시작: 2026-10-16 21:01:48 KST
- 위치: 백업 VM의 VPC 내부
- 방법: 허용 규칙 우선순위를 800으로 조정한 뒤 DB 3노드 GET /metrics 조회
- 통과 기준: 3/3 HTTP 200, primary=1 1개, postgres_running=1 3개, 임시 키 제거
- 종료: 2026-10-16 21:02:00 KST
- 관측: {"nodes": [{"http": "ok", "primary": null, "running": null}, {"http": "ok", "primary": null, "running": null}, {"http": "ok", "primary": null, "running": null}], "priority": 800, "run_id": "DR-BARMAN-FIREWALL-20261016-R3", "ssh_exit": 0, "temporary_key_removed": true}
- 판정: 실패
