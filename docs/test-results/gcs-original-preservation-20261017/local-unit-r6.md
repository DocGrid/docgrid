# 증분 감사 단위 시험 · R6

| 항목 | 값 |
| --- | --- |
| 목적 | 이전 해시 원장 재사용·새 객체 재해시·삭제된 사본 탐지 확인 |
| 실행 위치 | 로컬 작업 사본, GCP 미사용 |
| 방법 | `python3 -m unittest -v test_verify_gcs_original_preservation.py` |
| 실행 ID | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R6` |
| 성공 기준 | 모든 단위 시험 통과 |
| 시각 | 2026-10-17 01:54 KST |

## 관측

14/14개 통과, 실행 0.015초. 이전에 해시를 계산한 객체는 source·destination의 generation과 크기가 그대로일 때 다시 내려받지 않는다. 새 객체는 양쪽 바이트를 내려받아 SHA-256을 계산하며, 전에 검증한 대상 객체가 목록에서 사라지거나 generation이 바뀌면 실패한다. 이 시험은 로컬 함수 경계를 검증했고 GCS 전체 실행 결과는 별도 기록한다.
