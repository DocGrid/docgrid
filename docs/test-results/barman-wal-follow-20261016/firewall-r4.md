# DR-BARMAN-FIREWALL-20261016-R4 — Patroni 라벨 지표 판정

- 시작: 2026-10-16 21:02:20 KST
- 위치: 백업 VM의 VPC 내부
- 방법: DB 3노드 GET /metrics 응답에서 라벨이 있는 patroni_primary·patroni_postgres_running 추출
- 통과 기준: 3/3 HTTP 200, 각 노드 지표 2개, primary=1 1개, running=1 3개, 임시 키 제거
- 종료: 2026-10-16 21:02:31 KST
- 관측: {"nodes": [{"http": "ok", "matching_samples": 2, "primary": 0, "running": 1}, {"http": "ok", "matching_samples": 2, "primary": 1, "running": 1}, {"http": "ok", "matching_samples": 2, "primary": 0, "running": 1}], "run_id": "DR-BARMAN-FIREWALL-20261016-R4", "ssh_exit": 0, "temporary_key_removed": true}
- 판정: 통과
