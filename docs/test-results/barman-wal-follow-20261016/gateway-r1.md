# DR-BARMAN-GATEWAY-20261016-R1 — 지표 게이트웨이 접근

- 시작: 2026-10-16 20:51:23 KST
- 위치: 백업 VM의 VPC 내부
- 방법: 임시 OS Login 키로 접속해 DB 3노드의 GET /metrics만 조회
- 통과 기준: 응답 2노드 이상, primary=1 노드 정확히 1개, 임시 키 제거
- 종료: 2026-10-16 20:51:44 KST
- 관측: {"metrics": {"nodes": 3, "primary": 0, "reachable": 0}, "ssh_exit": 0, "stage": "gateway_probe"}
- 임시 키 제거: True
- 판정: 실패
