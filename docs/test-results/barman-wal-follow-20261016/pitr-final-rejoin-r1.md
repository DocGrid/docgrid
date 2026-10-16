# DR-BARMAN-PITR-FINAL-REJOIN-20261016-R1 — 원본 클러스터 재합류

- 시각: 2026-10-16 23:18:49~23:19:01 KST.
- 목적·위치: GCP 시험용 OpenSQL 3노드 및 백업 VM.
- 방법: Patroni 역할·복제 지연, Barman 검사와 감시기 모델 정렬을 읽기 전용으로 조회.
- 통과 기준: Primary 1대·Standby 2대 streaming, lag 0, Barman 검사 3/3 OK.
- 관측: Primary 순번 1, Standby 2대 streaming·lag [0,0], Barman 검사 3/3 OK, 감시기 모델 정렬, 기준 백업 1개.
- 판정: **통과**. 임시 OS Login 키 제거 명령 성공. 원격 전체 stdout 미보존; 도구 출력의 사후 비식별 요약.
