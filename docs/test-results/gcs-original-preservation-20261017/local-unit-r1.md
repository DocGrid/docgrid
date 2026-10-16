# GCS 원본 보존 감사 단위 시험 · LOCAL-UNIT-R1

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 원본·사본 generation 분리 기록, 누락·동일 크기 변조 탐지, 원본 삭제 후 기존 사본 유지 판정 |
| 실행 위치 | 격리 작업 트리의 로컬 Python |
| 명령 | `PYTHONPYCACHEPREFIX=<임시 경로> python3 scripts/opensql/test_verify_gcs_original_preservation.py -v` |
| 실행 ID | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R1` |
| 성공 기준 | 5개 시험 전부 통과, 누락·변조를 각각 실패로 분류 |

## 관측 결과

2026-10-17 01:17 KST에 실행했다. 5개 중 5개가 통과하고 실패 0개였다. 같은 크기의 다른 바이트는 `sha256_mismatch`, 누락된 사본은 `missing_copy`로 거부됐다. 삭제된 원본의 기존 사본은 이전 감사 원장과 SHA-256이 일치할 때만 보존 상태로 판정했다.
