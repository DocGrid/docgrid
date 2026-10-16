# 시험 원본 정리 후 전체 감사 · AUDIT-AFTER-CLEANUP-R6

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 시험 객체 정리 뒤 문서 원본 전체와 독립 보존본의 바이트·generation 연속성을 확인 |
| 실행 위치 | 로컬 격리 임시 디렉터리, 두 GCS 버킷 읽기 전용 |
| 명령 | `python3 scripts/opensql/verify_gcs_original_preservation.py --config <비공개 설정>` |
| 실행 ID | `GCS-ORIGINAL-AUDIT-AFTER-CLEANUP-20261017-R6` |
| 성공 기준 | 문서 원본 94건 전부의 보존본 일치, 누락·크기·SHA-256·generation 변경 0건; 시험 보존본은 retained로 별도 집계 |

## 관측 결과

2026-10-17 약 02:02 KST에 로컬에서 성공했다. 현재 원본 **94건**, 정확한 경로의 유효 보존본 **97건**, 삭제된 시험 원본의 보존본 **3건**으로 집계됐다. 누락·크기·generation 변경 **0건**, 처음 발견된 retained **0건**이었다. 이전에 각 객체의 SHA-256을 검증한 원장과 양쪽 generation·크기가 그대로여서 이번 회차 다운로드는 원본 **0건**, 대상 **0건**이다. 이는 전체 바이트를 다시 다운로드한 결과가 아니라 **불변 generation과 이전 해시 원장에 기반한 증분 검증**이다. 경로 오류 시험 사본 **2건**은 `ignored_invalid_destination`으로 계속 별도 표시했다.
