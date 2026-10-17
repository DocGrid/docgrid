# 외부 export 격리 import·검증 · IMPORT-R1

## 실행 전 계획 (2026-10-17 04:04 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-IMPORT-R1` |
| 목적 | 별도 stage의 외부 export를 새 Barman 카탈로그에 import하고 verify-backup 성공 확인 |
| 위치·방법 | 백업 VM에서 export 식별 파일과 현재 시험 DB systemid를 대조해 전용 카탈로그에만 기록한 뒤 import·verify |
| 합격 기준 | 식별 일치, 격리 백업 1건 DONE, verify 성공, 원본 Barman 카탈로그 1건 유지, 임시 키 제거 |
| 한계 | 아직 실제 PostgreSQL 실행 또는 Job 상태 복원을 시험한 것이 아님 |

## 관측

2026-10-17 04:03:47~04:04:05 KST에 외부 export 검증 **3/3**, 시스템 식별 일치, 격리 카탈로그 import **1건**, verify-backup 성공, 원본 카탈로그 **1건 유지**, 임시 SSH 키 제거 성공을 확인했다. **통과**.
