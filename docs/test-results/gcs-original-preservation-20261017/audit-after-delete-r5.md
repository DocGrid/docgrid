# 원본 삭제 후 사본 감사 · AUDIT-AFTER-DELETE-R5

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 원본에서 시험 객체를 삭제한 뒤 전체 현재 원본·사본의 해시와 보존 사본 건수를 확인 |
| 실행 위치 | 로컬 격리 임시 디렉터리, 두 GCS 버킷 읽기 전용 |
| 명령 | `python3 scripts/opensql/verify_gcs_original_preservation.py --config <비공개 설정>` |
| 실행 ID | `GCS-ORIGINAL-AUDIT-AFTER-DELETE-20261017-R5` |
| 성공 기준 | 현재 원본 전부의 대상 사본과 SHA-256이 일치하고, 삭제한 시험 원본의 대상 사본이 retained로 계수됨; 이전 감사 원장에 없었던 삭제 건은 unattributed로 구분 |

## 관측 결과

2026-10-17 01:45~01:47 KST에 로컬에서 실행했습니다. 현재 원본 **96건**, 유효 사본 **97건**의 키·크기·SHA-256을 대조해 누락·내용 불일치 **0건**으로 통과했습니다. 원본에서 지운 시험 객체의 사본 **1건**은 `retained=1`로 남았습니다. 이 객체는 직전 전체 감사 후에 생성·삭제됐으므로 이전 감사 원장에는 없어 `unattributed_retained=1`로 정직하게 구분했습니다. 해당 객체는 [삭제 시험 R2](delete-retention-r2.md)에서 별도로 삭제 전후 바이트·generation을 대조했습니다. 경로 오류 시험 사본 **2건**은 `ignored_invalid_destination=2`로 계속 분리됐습니다.
