# 중단 Job 시점의 격리 DB 기동 · PITR-RUNTIME-R1

## 실행 전 계획 (2026-10-17 04:11 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-PITR-RUNTIME-R1` |
| 목적 | 외부 백업·WAL만으로 구성한 별도 PGDATA를 기동해 실제 `PROCESSING` Job·Attempt를 읽는다 |
| 위치·방법 | 30분 자동 원복 timer가 켜진 시험 Standby 1대를 잠시 중단, 동일 허가 OpenSQL 컨테이너에서 별도 PGDATA를 localhost 포트 15432로 실행, 대상 Job 상태 SQL 조회 |
| 합격 기준 | 복원 DB가 Primary로 기동, 합성 버전 1·Job `PROCESSING`·Attempt 1건, 원래 PGDATA 무변경, 자동 원복 timer 활성 |
| 보호 | 실패 시 즉시 원복 스크립트 실행. 시험 중 원래 클러스터는 리더 1·Standby 1로 낮아짐. timer 만료 전에 사용자 기능 시험과 수동 원복 완료 |

## 관측

2026-10-17 04:11:08~04:11:26 KST에 기존 Standby를 중단하고 별도 PGDATA 기동을 시도했으나 **권한 거부**로 실패했다. 즉시 원복 스크립트가 성공했고 원래 Patroni·PostgreSQL 프로세스가 돌아왔으며 격리 postmaster PID는 없었다. 04:11:32~04:11:41 KST 재점검에서 리더 **1**·streaming Standby **2**, 지연 **0·0바이트**, Barman **3/3 OK**다. **시험 실패·안전 원복 통과**. 이 회차를 성공으로 합산하지 않는다.
