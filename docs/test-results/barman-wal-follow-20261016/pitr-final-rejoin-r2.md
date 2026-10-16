# DR-BARMAN-PITR-FINAL-REJOIN-20261016-R2 — 정리 후 최종 상태 재확인

- 시각: 2026-10-16 23:28:19~23:28:30 KST.
- 목적·위치: 격리 stage와 시험 기록을 정리한 뒤 GCP 시험용 OpenSQL 3노드·백업 VM 상태 재확인.
- 방법: 백업 VM에서 SQL 역할·복제 상태, Barman 검사, 1분 timer와 감시기 정렬을 읽기 전용 조회.
- 통과 기준: Primary 1대, Standby 2대 streaming·lag 0바이트, Barman streaming·slot·receive-wal 검사 모두 OK, timer 활성·모델 정렬.
- 관측: Primary 순번 1, Standby 2대 streaming·lag [0,0]바이트, 기준 백업 1개, Barman 3검사 OK, timer 활성·감시기 정렬. SSH 성공, 임시 OS Login 키 제거 명령 성공.
- 판정: **통과**. 1회 시점 조회이며 장기 안정성이나 사용자 RPO·RTO를 증명하지 않는다. 도구 출력의 사후 비식별 요약이며 원격 전체 stdout은 지속 로그로 저장하지 않았다.
