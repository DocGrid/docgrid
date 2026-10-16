# DR-BARMAN-PITR-WAL-STAGE-20261016-R1 — timeline WAL 준비

- 목적·위치: GCP 백업 VM의 격리 복원 stage.
- 방법: Barman archive의 timeline 18·19·20 WAL/history를 복원 stage에 복사하고 원본·사본 해시 대조.
- 통과 기준: 필요한 파일 9개와 해시 불일치 0건.
- 관측: 파일 9개·83,889,096바이트·해시 불일치 0건. 실제 PostgreSQL replay 성공 여부는 별도 Run으로 판정.
- 판정: **통과**. 원격 전체 stdout 미보존; 도구 출력의 사후 비식별 요약.
