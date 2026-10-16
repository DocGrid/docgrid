# DR-BARMAN-SWITCHOVER-POSTCHECK-20261016-R1 — 전환 직후 Barman 상태

- 시각: 2026-10-16 22:15:25~22:15:36 KST.
- 목적·위치: GCP 백업 VM에서 새 Primary 확인 후 Barman 감시기·수신 상태 확인.
- 방법: 세 노드 SQL, `barman check`, timer, 감시기 dry-run 읽기 전용 조회.
- 통과 기준: 리더 순번 1, Standby 2/2 streaming, Barman 세 검사 OK, 감시기 모델 정렬.
- 관측: DB 역할·lag 0은 정상. 그러나 감시기 모델 미정렬, replication slot·receive-wal 검사는 FAILED, timer는 active. 임시 OS Login 키 제거 명령 성공.
- 판정: **실패**. 전환 직후 약 45초에는 백업 수신 경로가 아직 새 리더로 수렴하지 않았다.
