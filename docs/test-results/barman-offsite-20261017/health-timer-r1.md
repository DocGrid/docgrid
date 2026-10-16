# 마지막 성공 시각 자동 점검 · HEALTH-TIMER-R1

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 마지막 성공 시각을 systemd health timer가 5분마다 자동 검사하는지 확인 |
| 실행 위치 | GCP 시험용 Barman 백업 VM |
| 절차 | health service·timer 설치 및 `systemd-analyze verify` → timer 활성 → 예정된 회차의 서비스 종료 코드·나이 초·최근 오류 확인 |
| 실행 ID | `BARMAN-OFFSITE-HEALTH-20261017-R1` |
| 성공 기준 | timer enabled·active, 예정된 회차 종료 코드 0, 성공 경과 900초 이하, 최근 오류 없음 |

## 관측 결과

2026-10-17 01:02:13 KST의 첫 예약 회차를 확인했다. health timer는 `enabled`·`active`였고, 서비스는 `success`·종료 코드 0이었다. 같은 시점의 수동 `check`는 `healthy=true`, 마지막 동기화 성공 후 65초를 보고했다. 이전 실패 주입은 격리된 임시 설정을 사용했으므로 실제 상태 파일의 최근 오류는 없었다. 한 회차를 관측한 것이며 장기 운영이나 외부 알림 수신은 아직 검증하지 않았다.
