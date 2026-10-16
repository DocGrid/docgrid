# Barman 외부 보존 정기 실행 점검 · TIMER-R1

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 수동 전송 성공 뒤 5분 timer를 켜고 다음 회차의 멱등 동기화·건강 상태 확인 |
| 실행 위치 | GCP 시험용 Barman 백업 VM |
| 절차 | `systemctl enable --now barman-offsite-sync.timer` → 예약 상태 확인 → 다음 회차 후 서비스 결과·완료 표시 수·WAL 수·`check` 확인 |
| 실행 ID | `BARMAN-OFFSITE-TIMER-20261017-R1` |
| 성공 기준 | timer 활성, 다음 회차 서비스 종료 코드 0, 완료 백업 표시 1개 유지, WAL 9개 이상·미완성 0개, 마지막 성공 시각 갱신 |

## 관측 결과

2026-10-17 00:54~00:55 KST timer를 활성화했고 enabled·active·다음 실행 예약을 확인했다. 00:55 회차 뒤 서비스 결과 `success`, 종료 코드 0, 마지막 성공 시각 변경, 완료 백업 1개, 로컬 완성 WAL 9개, 최근 오류 없음이었다. GCS 재조회에서도 완료 표시 1개, 유효 WAL 9개, `.partial` 0개가 유지됐다. 시간순 결과는 [이벤트 로그](timer-r1.events.jsonl)에 남겼다. 다음 주기 이후 장기 연속성은 아직 관측하지 않았다.
