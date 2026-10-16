# 시험용 복원 데이터·자동 원복 타이머 정리 · CLEANUP-STAGE-R1

## 실행 전 계획 (2026-10-17 03:04 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-CLEANUP-STAGE-R1` |
| 목적 | 정상 재합류를 확인한 뒤 시험용 별도 PGDATA와 자동 원복 예약만 정리 |
| 위치·방법 | 시험 Standby에서 Patroni·PostgreSQL 정상 실행, 격리 DB 중단, 경로가 정확히 시험 경로인지 확인 후 timer 중지·별도 디렉터리 제거 |
| 합격 기준 | timer 비활성, 격리 데이터 경로·포트 없음, 원래 데이터 경로와 Patroni·PostgreSQL 실행 유지 |
| 보호 | 경로가 예상과 다르거나 symlink·실행 중이면 삭제하지 않음. 원래 PGDATA는 읽기만 함 |

## 관측

2026-10-17 **03:04:47~03:04:55 KST**에 원래 Patroni·PostgreSQL 실행과 격리 DB 중단을 확인한 후, 자동 원복 timer를 중지하고 시험용 별도 데이터 경로·원복 스크립트만 제거했다. timer 비활성, 시험 경로 부재, 격리 포트 폐쇄, 원래 데이터 경로 존재 및 Patroni·PostgreSQL 실행을 **6/6 확인**했다. 임시 OS Login 키 제거 성공. **격리 호스트 정리 통과**.
