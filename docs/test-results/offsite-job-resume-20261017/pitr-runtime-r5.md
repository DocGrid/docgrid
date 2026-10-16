# Attempt 이후 목표의 격리 DB 기동 · PITR-RUNTIME-R5

## 실행 전 계획 (2026-10-17 04:28 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-PITR-RUNTIME-R5` |
| 목적 | 새 목표 시각에서 Job PROCESSING과 Attempt 1건이 함께 복원되는지 확인 |
| 위치·방법 | 최신 30분 timer·3노드 상태 확인 후 시험 Standby 한 대만 localhost 격리 DB로 전환, SQL 조회 |
| 합격 기준 | 격리 DB Primary, 버전 1·Job PROCESSING·Attempt 1, timer 활성 |
| 보호 | 실패 시 즉시 원복, 성공하면 앱 시험 동안 timer 유지 |

## 관측

2026-10-17 04:28:06~04:28:16 KST에 별도 OpenSQL 시작 성공, SQL 두 번째 조회에서 격리 DB Primary·합성 버전 **1건**·Job `PROCESSING`·Attempt **1건**을 확인했다. 자동 원복 timer 활성이고 원래 DB 데이터 경로는 사용하지 않았다. **PITR·중단 Job 복원 통과**. 원래 클러스터는 시험 동안 리더 1·Standby 1이다.
