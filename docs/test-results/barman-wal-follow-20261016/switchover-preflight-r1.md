# DR-BARMAN-SWITCHOVER-PREFLIGHT-20261016-R1 — 계획 전환 안전 관문

- 시각: 2026-10-16 22:12:02~22:12:13 KST.
- 목적·위치: GCP 백업 VM에서 계획 전환 전 3노드 복제·백업·감시기 상태를 읽기 전용 점검.
- 방법: SQL 역할·재생 LSN·WAL receiver, `barman check`, 백업 목록, timer, 감시기 dry-run 조회.
- 통과 기준: Primary 1·스트리밍 Standby 2, 후보 지연 16MiB 미만, 기준 백업 1개 이상, 세 Barman 검사 OK, timer active·모델 정렬.
- 관측: Primary 순번 2, 후보 순번 1, Standby 2/2 streaming·지연 0바이트, 기준 백업 1개, 세 검사 OK, timer active·모델 정렬. 임시 OS Login 키 제거 명령 성공.
- 판정: **전환 가능**. 이 Run은 상태만 읽었다.
