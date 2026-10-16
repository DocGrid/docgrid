# DR-BARMAN-SWITCHOVER-POSTCHECK-20261016-R2 — 새 리더 추적 수렴

- 시각: 2026-10-16 22:16:11~22:16:22 KST.
- 목적·위치: GCP 백업 VM에서 전환 뒤 Barman이 새 Primary를 따라 수신하는지 재검증.
- 방법·통과 기준: [첫 점검](switchover-postcheck-r1.md)과 동일한 읽기 전용 조회.
- 관측: 리더 순번 1, Standby 2/2 streaming·지연 0바이트, 기준 백업 1개, timer active, 감시기 모델 정렬, Barman streaming·slot·receive-wal 모두 OK. 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**. 첫 실패 시각과 이 성공 시각 사이에 수렴했으나, 정확한 수신 중단 시간은 계측하지 않았다.
