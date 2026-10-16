# DR-BARMAN-PITR-FILES-20261016-R1 — 기준 백업 복원

- 목적·위치: GCP 백업 VM의 격리 stage에 timeline 18 기준 백업을 복원하고 timeline 20 목표 시각을 지정.
- 방법: `barman verify-backup` 후 `barman recover --no-get-wal --target-tli 20 --target-time '2026-10-16 13:32:35.554380+00:00' --target-action promote`.
- 통과 기준: 복원 파일과 복구용 WAL이 격리 경로에 준비됨.
- 관측: 기준 백업 검증은 통과했으나 `--no-get-wal` 선택으로 `pg_wal`은 비었다. 이 Run만으로 복구 가능 판정 불가.
- 판정: **부분 통과**. 뒤이은 WAL stage와 격리 SQL 검증이 필요했다. 원격 전체 stdout은 지속 로그로 저장하지 않아 이 문서는 도구 출력의 사후 비식별 요약이다.
