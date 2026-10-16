# 외부 WAL만 포함한 격리 복원 파일 준비 · RECOVER-FILES-R1

## 실행 전 계획 (2026-10-17 02:47 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-RECOVER-FILES-R1` |
| 목적 | 외부 export에서 import한 기준 백업과 외부에서 다운로드한 WAL만 사용해 PITR 데이터 디렉터리 준비 |
| 위치·방법 | 백업 VM의 격리 Barman 설정에서 `recover --no-get-wal --target-tli 20 --target-time 2026-10-16 13:32:35.554380+00:00 --target-action promote`. 생성한 별도 PGDATA의 `pg_wal`에 외부 WAL·history 9개를 복사해 SHA-256 대조 |
| 합격 기준 | `recovery.signal`과 목표 시각 설정 존재, WAL/history 9건·해시 불일치 0건, 기존 카탈로그·PGDATA 무변경 |
| 제한 | 이 단계는 파일 준비에 한정. PostgreSQL 실행과 마커 A/B 판정은 이후 허가된 격리 호스트에서 별도로 수행 |
| 보호 | 원본 Barman의 `wals_directory`는 복원 입력으로 사용하지 않는다. 원문 출력에 포함될 수 있는 내부 주소·경로는 기록하지 않음 |

## 관측

2026-10-17 **02:46:47~02:46:59 KST**에 격리 Barman 카탈로그의 백업으로 목표 timeline **20**·시각 **2026-10-16 22:32:35.554380 KST**의 데이터 디렉터리를 준비했다. `recovery.signal`과 목표 설정이 생성됐다. 외부에서 내려받은 WAL/history **9개**, **83,889,096바이트**를 복원 디렉터리에 배치했고 파일별 SHA-256 불일치 **0건**이었다. 세그먼트 timeline 18·19·20과 history 17·18·19·20을 확인했다. 임시 OS Login 키 제거 성공. **파일 준비 통과**. DB 프로세스를 실행하거나 A/B 마커를 판정한 것은 아니다.
