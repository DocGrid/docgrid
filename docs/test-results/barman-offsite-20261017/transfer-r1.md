# Barman 기준 백업·WAL 실제 전송 · TRANSFER-R1

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 완료 기준 백업 1개와 보관 중인 WAL·timeline 기록을 백업 VM 밖의 전용 GCS 버킷에 저장·대조 |
| 실행 위치 | GCP 시험용 Barman 백업 VM과 전용 비공개 GCS 버킷 |
| 절차 | `barman_offsite_sync.py sync` 수동 1회 → GCS 완료 표시·아카이브 크기·CRC32C·WAL 객체 수 확인 → timer 활성화 → `check` |
| 실행 ID | `BARMAN-OFFSITE-TRANSFER-20261017-R1` |
| 성공 기준 | Barman 완료 기준 백업 1개가 완료 표시와 함께 보존, 완성 WAL·history 누락 0건, 크기·CRC32C 불일치 0건, 상태 점검 정상 |

## 관측 결과

2026-10-17 00:47 KST 수동 실행은 **실패**했다. 서비스 종료 코드 1, 단계 `no_completed_backup`, 마지막 성공 기록 없음. Barman에는 완료 백업 1개가 있었지만 실제 출력 `Status                 : DONE`의 공백을 판정식이 허용하지 않아 거부했다. 외부 전송은 시작되지 않았다. 판정식을 수정하고 별도 R2에서 재실행한다. 시간순 관측은 [안전한 이벤트 로그](transfer-r1.events.jsonl)에 남겼다.
