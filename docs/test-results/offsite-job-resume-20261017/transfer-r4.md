# 세 번째 복원 파일을 새 Standby stage로 전송 · TRANSFER-R4

## 실행 전 계획 (2026-10-17 04:26 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-TRANSFER-R4` |
| 목적 | `recovered-r3` 자체를 사용하지 않은 Standby 별도 경로로 옮김 |
| 위치·방법 | 백업 VM → 압축 메모리 스트림 → Standby. 송·수신 파일 수·바이트·트리 SHA 대조 |
| 합격 기준 | 양단 종료 0, 전체 트리 일치, WAL/history 11개, recovery.signal |
| 보호 | 기존 실패 stage·원래 PGDATA 무변경 |

## 관측

2026-10-17 04:25:39~04:26:23 KST에 송·수신 **1,906개·251,395,458바이트**와 트리 SHA-256이 일치했다. WAL/history **11개**, 양단 종료 0, 임시 키 **2/2개 제거**. **통과**.
