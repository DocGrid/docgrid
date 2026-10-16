# 격리 OpenSQL 종료와 Standby 재합류 · CLEANUP-R1

## 실행 전 계획 (2026-10-17 03:01 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-CLEANUP-R1` |
| 목적 | 시험용 복원 DB를 종료하고 원래 OpenSQL Standby를 정상 복제 상태로 되돌림 |
| 위치·방법 | 시험 Standby의 사전 설치된 자동 원복 스크립트를 수동 실행. 원래 Patroni·PostgreSQL 및 격리 포트 폐쇄를 확인한 뒤 전체 3노드·Barman 상태 재확인 |
| 합격 기준 | 리더 1·streaming Standby 2, 격리 15432 포트 폐쇄, Barman 수신 활성, 자동 원복 timer 비활성, 원래 PGDATA 무변경 |
| 보호 | 원복 스크립트는 별도 격리 데이터 경로만 제거. 원래 DB 데이터 경로에는 삭제 명령 없음 |

## 관측

2026-10-17 **03:03:35~03:03:50 KST**에 사전 등록한 원복 스크립트를 수동 실행했다. 종료 코드 0, 원래 Patroni·PostgreSQL 프로세스 실행, 격리 DB의 `postmaster.pid` 부재를 확인했다. 자동 원복 timer는 이 단계에서 계속 활성으로 남겨 사후 복제 검사 뒤 별도 정리했다. 임시 OS Login 키 제거 성공. **프로세스 원복 통과**. 전체 복제·WAL 수신은 별도 사후 점검에서 판정한다.
