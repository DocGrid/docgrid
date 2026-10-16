# DR-BARMAN-FIREWALL-20261016-R2 — 방화벽 적용 후 재조회

- 시작: 2026-10-16 20:59:49 KST
- 위치: 백업 VM의 VPC 내부
- 방법: DB 3노드 TCP 18008 연결과 GET /metrics 조회
- 통과 기준: 3/3 HTTP 200, primary=1 정확히 1개, 임시 키 제거
- 종료: 2026-10-16 21:00:10 KST
- 관측: {"nodes": [{"http": "unknown", "primary": null, "running": null, "tcp": "timeout"}, {"http": "unknown", "primary": null, "running": null, "tcp": "timeout"}, {"http": "unknown", "primary": null, "running": null, "tcp": "timeout"}], "run_id": "DR-BARMAN-FIREWALL-20261016-R2", "ssh_exit": 0, "temporary_key_removed": true}
- 판정: 실패
