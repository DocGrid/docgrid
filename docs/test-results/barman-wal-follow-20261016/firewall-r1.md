# DR-BARMAN-FIREWALL-20261016-R1 — 백업 VM의 역할 지표 경로

- 시작: 2026-10-16 20:59:03 KST
- 위치: GCP VPC 및 백업 VM
- 목적: 백업 VM 단일 /32에서 DB 3노드의 GET /metrics 전용 포트 접근
- 방법: 대상 DB 공통 태그·출발지 단일 VM·TCP 18008만 허용 후 각 노드의 응답과 역할 확인
- 통과 기준: 3/3 응답, primary=1 정확히 1개, postgres_running=1 3개, 임시 SSH 키 제거
- 종료: 2026-10-16 20:59:30 KST
- 관측: {"firewall": "created", "metrics": [{"primary": null, "reachable": false, "running": null}, {"primary": null, "reachable": false, "running": null}, {"primary": null, "reachable": false, "running": null}], "scope": {"backup_count": 1, "db_count": 3, "same_network": true, "shared_db_tag_count": 1, "source_host_count": 1}, "ssh_exit": 0, "temporary_key_removed": true}
- 판정: 실패
