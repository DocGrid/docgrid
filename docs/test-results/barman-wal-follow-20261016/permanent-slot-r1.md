# DR-BARMAN-PERMANENT-SLOT-20261016-R1 — Barman 슬롯의 Patroni 영구 등록

- 시작·종료: 2026-10-16 22:04:31~22:04:48 KST.
- 목적·위치: GCP Patroni 리더의 DCS 공유 설정에 기존 Barman 슬롯을 영구 물리 슬롯으로 등록하려는 첫 시도.
- 방법: Barman 설정의 슬롯 이름을 메모리에서만 읽고 리더 역할과 4GB WAL 보존 한도를 확인했다. 변경 전 DCS 설정을 비공개 0600 파일에 보관하고 로컬 `PATCH /config`로 `postgresql.slots`에 `type=physical`을 추가했다. 슬롯 이름·주소·설정 원문 미기록.
- 통과 기준: 응답 수락과 4GB 한도 유지에 더해, 후속 SQL 조회에서 Standby 2대의 실제 슬롯 생성 확인.
- 관측: HTTP 200, 응답에 중첩 슬롯 정의 1개, 4GB 한도 유지, 비공개 백업 생성, 임시 키 제거 명령 성공. 그러나 [첫 조회](slot-topology-r5.md)와 [재조회](slot-topology-r6.md)에서 Standby 슬롯은 생성되지 않았다.
- 판정: **실패**. API 응답만으로 성공이라고 판정한 초기 기록을 정정한다. Patroni가 사용하는 키는 최상위 `slots`이며, [후속 수정](permanent-slot-r2.md)에서 중첩 키를 제거했다.
