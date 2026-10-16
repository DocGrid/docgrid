# DR-BARMAN-PITR-CONTAINER-20261016-R3 — 새 timeline 격리 PITR

- 시각: 2026-10-16 23:18:15~23:18:33 KST.
- 목적·위치: GCP 시험 Standby 1대의 원본과 분리된 stage, `127.0.0.1:15432`.
- 방법: timeline 18 기준 백업과 timeline 19·20 WAL을 적용한 stage를 격리 실행. SQL로 A·B 개수와 `pg_is_in_recovery()` 확인 후 격리 서버를 내리고 원래 Standby bootstrap.
- 통과 기준: 목표 시각 A 1건·B 0건, 복구 모드 false, Patroni/DB 재합류.
- 관측: 격리 시작 0.285초; A 1건·B 0건; 복구 모드 false. 원복 뒤 Patroni 2개, PostgreSQL 8개 프로세스를 확인했다. 별도 최종 검사는 Primary 1대·Standby 2대 streaming을 확인했다.
- 판정: **통과**. 사용자 RPO·RTO와 GCS 동작은 이 Run에서 측정하지 않았다. 원격 전체 stdout 미보존; 도구 출력의 사후 비식별 요약.
