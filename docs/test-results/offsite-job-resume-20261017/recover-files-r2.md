# Attempt 시각으로 새 격리 PGDATA 준비 · RECOVER-FILES-R2

## 실행 전 계획 (2026-10-17 04:18 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-RECOVER-FILES-R2` |
| 목적 | 실제 Attempt 시작 시각을 복구 목표로 사용해 도달 불가능한 시간 목표 가능성을 제거 |
| 위치·방법 | 동일 외부 export·완료 WAL 11개와 별도 Barman 카탈로그로 새 `recovered-r2` PGDATA 구성 |
| 합격 기준 | 새 recovery.signal·목표 시각 설정, WAL/history 11개 이상·SHA 불일치 0, 이전 실패 stage와 원본 카탈로그 불변 |
| 주의 | 이 단계는 파일 준비이며 DB 기동 및 Job 결과를 입증하지 않음 |

## 관측

2026-10-17 04:18:02~04:18:14 KST에 새 PGDATA의 `recovery.signal`·목표 설정, 외부 WAL/history **11개·117,443,528바이트**, SHA 불일치 **0건**을 확인했다. 이전 실패 stage와 원본 카탈로그는 변경하지 않았다. **파일 준비 통과**.
