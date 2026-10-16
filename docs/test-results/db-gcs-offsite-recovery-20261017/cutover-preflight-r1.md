# Standby 격리 실행 직전 복제 상태 · CUTOVER-PREFLIGHT-R1

## 실행 전 계획 (2026-10-17 02:50 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-CUTOVER-PREFLIGHT-R1` |
| 목적 | Standby 1대를 잠시 분리하기 직전 리더·두 복제본·Barman 수신이 정상인지 재확인 |
| 위치·방법 | 백업 VM에서 3노드 SQL·LSN, Barman 검사·리더 추적 상태를 읽기 전용 조회 |
| 합격 기준 | Primary 1, streaming Standby 2, 지연 각 16MiB 미만, Barman 검사 3/3 OK. 실패 시 격리 실행 중단 |
| 보호 | 원본 DB·카탈로그 변경 없음 |

## 관측

2026-10-17 **02:49:57~02:50:07 KST**에 재확인했다. Primary **1대**, streaming Standby **2대**, 지연 각각 **0바이트**였다. Barman의 streaming·slot·receive-WAL **3/3 OK**, 백업 1건, 리더 추적 timer 활성·리더 정렬. 임시 OS Login 키 제거 성공. **통과**.
