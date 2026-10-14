# DR-PITR-FILES-20261015-R1 — 시점 복원 파일 준비

- 목적·성공 기준: 기준 백업과 이어지는 WAL로 T에 맞춘 파일을 생성하고 비공개 압축본을 보존한다.
- 위치·절차: 백업 VM에서 Barman `recover --no-get-wal --target-time ... --target-action promote`, `recovery.signal`과 로컬 WAL 확인, 비공개 압축본 저장.
- 시각: Barman 목표 시점 파일 복원 2026-10-15 03:20:36~03:20:39 KST, 압축본 작성 03:21:45~03:21:55 KST.
- 관측: 복원에 필요한 WAL 파일 4개·167,642,944바이트를 준비했다. 보존 압축본은 19,130,509바이트. **통과.**
- 범위: 허가된 Standby 호스트로 옮긴 [전송 실패](pitr-transfer-r1.md)와 [재시도 성공](pitr-transfer-r2.md)은 별도 기록이다. 라이선스 실행 전 기존 호스트의 읽기 가능한 허가 파일 1개를 확인했으며 DB 런타임 결과는 [별도 시험](pitr-runtime-r1.md)에 있다.
