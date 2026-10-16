# DR-BARMAN-WATCHER-INIT-20261016-R1 — 현재 리더로 최초 모델 적용

- 시각: 2026-10-16 22:07:53~22:08:04 KST.
- 목적·위치: GCP 백업 VM에서 감시기를 수동 실행해 현재 Primary에 Barman 모델을 맞춤.
- 방법: `barman` 계정으로 설치된 감시기를 비공개 설정과 함께 1회 실행하고 `barman check` 확인.
- 통과 기준: 리더 1대 식별, 모델 전환 1회, streaming·slot·receive-wal 3개 OK.
- 관측: 감시기 `healthy`, `switched=true`, 세 검사 OK. 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**.
