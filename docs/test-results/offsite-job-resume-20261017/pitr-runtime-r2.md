# 격리 DB 기동 재시험 · PITR-RUNTIME-R2

## 실행 전 계획 (2026-10-17 04:12 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-PITR-RUNTIME-R2` |
| 목적 | 별도 PGDATA 소유권 보정 후 외부 백업의 실제 DB 기동과 중단 Job 상태 확인 |
| 위치·방법 | 정상 3노드 재확인과 활성 30분 원복 timer를 전제로 시험 Standby 한 대만 전환, localhost 15432에 격리 DB 기동, 합성 Job·Attempt·버전 조회 |
| 합격 기준 | 복원 DB Primary, 버전 1·Job PROCESSING·Attempt 1, 원래 PGDATA 불변, 자동 원복 활성 |
| 보호 | 실패 시 즉시 원복 후 원래 리더 1·Standby 2 검증 |

## 관측

2026-10-17 04:12:37~04:13:27 KST에 별도 PostgreSQL 시작은 성공했지만 시험 Job SQL은 **20회 중 0회 성공**했다. 그 시점에는 오류 종류를 보존하지 않아 원인을 확정하지 않았다. 즉시 원복 성공·격리 PID 없음·timer 활성. 04:13:37~04:13:48 KST 재검증에서 리더 **1**·streaming Standby **2**, 지연 **0·0바이트**, Barman **3/3 OK**. **기동 부분 통과, Job 검증 실패**.
