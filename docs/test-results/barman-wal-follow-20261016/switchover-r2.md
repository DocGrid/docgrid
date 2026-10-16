# DR-BARMAN-SWITCHOVER-20261016-R2 — 계획 Primary 전환

- 시각: 2026-10-16 22:14:38~22:15:04 KST.
- 목적·위치: GCP 시험용 OpenSQL 3노드에서 사용자 승인 하에 계획된 리더 전환 후 재합류 확인.
- 방법: Patroni REST `host`와 VM 주소의 대응을 재검사하고, 기존 리더에서 `patronictl switchover --leader <기존 리더> --candidate <후보> --force`를 **1회** 실행. `/cluster`를 재조회했다.
- 통과 기준: 리더 순번 2→1, timeline 증가, 기존 리더 포함 Standby 2대 streaming 복귀.
- 관측: 실행 전 timeline 19·리더 순번 2·Standby 2대 streaming/lag 0. 전환 명령 3.430초·종료 코드 0. 22:14:51.706 새 리더가 보였고, 기존 리더는 일시 stopped. 22:15:03.738 두 Standby 모두 streaming/lag 0, timeline 20. 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**. 전환 시 앱 쓰기 성공률이나 사용자 RTO는 이 Run에서 측정하지 않았다.
