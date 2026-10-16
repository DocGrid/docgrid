# DR-BARMAN-WAL-ARCHIVE-20261016-R1 — 새 timeline 세그먼트 보관

- 시각: 2026-10-16 22:17:29~22:17:50 KST.
- 목적·위치: GCP 백업 VM에서 새 Primary의 WAL 세그먼트 전환을 요청하고 Barman 보관 여부 검증.
- 방법: `barman switch-wal --archive <서버>`를 1회 실행한 뒤 timeline 20 완성 세그먼트 수와 `barman check`를 조회.
- 통과 기준: 명령 종료 코드 0, 완성 세그먼트 수 증가, streaming·slot·receive-wal 세 검사 OK.
- 관측: 명령 10.348초·종료 코드 0, 완성 세그먼트 0→1개, 세 검사 OK. 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**.
