# DR-BARMAN-MODEL-INVENTORY-20261016-R2 — Barman 모델·수신 상태 조회

- 시작·종료: 2026-10-16 21:54:43~21:54:54 KST.
- 목적·위치: GCP 백업 VM에서 Barman 구성 모델 준비 상태와 기존 WAL 수신 상태 확인.
- 방법: `barman list-server --minimal`, `show-servers`, 설정 파일의 모델 표시 개수, `barman check`의 필요한 항목만 추출. 접속 문자열·서버 이름·슬롯 이름은 출력하지 않았다.
- 통과 기준: 서버 1개, 활성 모델과 수신 상태 확인, 임시 OS Login 키 제거.
- 관측: 서버 1개·구성 파일 1개·모델 표시 0개·활성 모델 없음. `create_slot=manual`, `streaming_archiver=True`. `barman check` 종료 코드 0, streaming·replication slot·receive-wal running 모두 OK. 임시 키 제거 명령 성공.
- 판정: **현재 수신 통과, 전환 모델 미준비**. 이 결과만으로 리더 변경 자동 추적을 시작할 수 없다.
