# 외부 입력으로 격리 PGDATA 준비 · RECOVER-FILES-R1

## 실행 전 계획 (2026-10-17 04:06 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-RECOVER-FILES-R1` |
| 목적 | 중단 Job이 존재하던 목표 시각까지 외부 완료 기준 백업·WAL만으로 새 PGDATA 구성 |
| 위치·방법 | 백업 VM의 격리 Barman 카탈로그에서 target timeline 20, 목표 시각의 `recover --no-get-wal` 실행. 외부 WAL/history 11개 이상을 새 PGDATA에 복사·해시 대조 |
| 합격 기준 | recovery.signal·목표 시각 설정, 외부 WAL 형식 적합·SHA 불일치 0, 원본 카탈로그·DB 불변 |
| 한계 | 이 단계는 파일 구성만 확인하며 PostgreSQL 기동 결과가 아니다 |

## 관측

2026-10-17 04:05:58~04:06:08 KST에 timeline **20**, 목표 시각의 `recovery.signal`·설정 존재, 외부 WAL/history **11개·117,443,528바이트**, SHA 불일치 **0건**을 확인했다. 임시 키 제거 성공. **파일 준비 통과**.
