# DR-BARMAN-MODEL-INVENTORY-20261016-R3 — 모델 최초 적용 후 검사

- 시각: 2026-10-16 22:07:20~22:07:29 KST.
- 목적·위치: GCP 백업 VM에서 Barman 서버·모델과 수신 상태 확인.
- 방법: `barman list-server`, `show-servers`, `check`를 실행하고 내부 식별자 없이 개수·검사 상태만 기록.
- 통과 기준: 서버 1개, 모델 3개, 활성 모델 설정, streaming·slot·receive-wal OK.
- 관측: 서버 1개·모델 표시 3개·활성 모델 설정, 세 검사 OK. 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**.
