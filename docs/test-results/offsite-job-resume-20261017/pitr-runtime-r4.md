# 새 목표 시각의 격리 DB 기동 · PITR-RUNTIME-R4

## 실행 전 계획 (2026-10-17 04:23 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-PITR-RUNTIME-R4` |
| 목적 | Attempt 시작 커밋 시각을 목표로 만든 새 PGDATA에서 중단 Job 1건을 복원 |
| 위치·방법 | 새 30분 자동 원복 timer와 3노드 정상 상태를 확인한 뒤 Standby 한 대만 별도 localhost DB로 전환 |
| 합격 기준 | 격리 DB Primary, 합성 버전 1·Job PROCESSING·Attempt 1, 원래 data 무변경, timer 활성 |
| 보호 | 실패 시 즉시 원복·3노드 확인. 성공 시 timer를 유지한 채 격리 앱 시험 후 수동 원복 |

## 관측

2026-10-17 04:22:51~04:23:41 KST에 별도 DB 기동과 SQL 접속은 성공했다. 복원 시점에는 버전 **1건**, Job `PROCESSING`이지만 Attempt **0건**이라 합격 기준 미달이었다. 즉시 원복 성공·격리 PID 없음. 04:24:31~04:24:42 KST 재검증에서 리더 **1**·streaming Standby **2**, 지연 **0·0바이트**, Barman **3/3 OK**. **부분 복원·Job 재개 씨앗 검증 실패**. 소스 이벤트를 읽어 `EMBEDDING_STARTED`가 Attempt 시작 뒤 1.48초에 기록된 것을 확인했으며, 해당 이벤트 시각으로 새 목표를 정한다.
