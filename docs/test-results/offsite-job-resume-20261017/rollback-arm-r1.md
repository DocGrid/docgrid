# Standby 자동 원복 예약 · ROLLBACK-ARM-R1

## 실행 전 계획 (2026-10-17 04:10 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-ROLLBACK-ARM-R1` |
| 목적 | 격리 DB 기동 중 세션이 끊겨도 원래 Patroni 복제본을 30분 내 자동으로 복귀시키는 안전장치 설치 |
| 위치·방법 | 시험 Standby에 별도 stage만 중단하고 원래 컨테이너·bootstrap을 재시작하는 스크립트와 단발 systemd timer 설치 |
| 합격 기준 | 스크립트 모드 0700, bash 구문 통과, timer active, 원래 Patroni는 아직 실행 중 |
| 보호 | 실패하면 원래 Standby를 중단하지 않음. 시험 후 정상 복귀를 확인하고 timer 제거 |

## 관측

2026-10-17 04:10:25~04:10:32 KST에 스크립트 **0700**, Bash 구문 통과, 30분 timer 활성, 설치 종료 코드 0, 임시 키 제거를 확인했다. **통과**. 원래 Standby는 이 시점까지 계속 실행 중이다.
