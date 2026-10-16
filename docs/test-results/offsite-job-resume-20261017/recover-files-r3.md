# Attempt 이후 인덱싱 이벤트 시각의 복원 파일 · RECOVER-FILES-R3

## 실행 전 계획 (2026-10-17 04:25 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-JOB-OFFSITE-20261017-RECOVER-FILES-R3` |
| 목적 | Attempt 이후의 EMBEDDING_STARTED 이벤트 시각을 목표로 해 Job·Attempt를 함께 복원 |
| 위치·방법 | 외부 export·완료 WAL 11개로 또 다른 PGDATA를 생성하고 SHA 대조 |
| 합격 기준 | recovery.signal·시각 설정, WAL/history 11개·SHA 불일치 0 |
| 한계 | 실제 Job·Attempt 상태는 DB 기동 뒤 판정 |

## 관측

2026-10-17 04:25:04~04:25:17 KST에 새 `recovery.signal`·목표 설정, 외부 WAL/history **11개·117,443,528바이트**, SHA 불일치 **0건**, 임시 키 제거를 확인했다. **파일 준비 통과**.
