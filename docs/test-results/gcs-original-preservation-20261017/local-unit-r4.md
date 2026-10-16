# GCS 원본 감사 generation 연속성 검사 · LOCAL-UNIT-R4

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 바이트가 같아도 이전 원장과 원본·사본 generation이 달라진 객체를 탐지 |
| 실행 위치 | 로컬 작업트리, 외부 버킷 접근 없음 |
| 명령 | `PYTHONPYCACHEPREFIX=<임시 경로> python3 scripts/opensql/test_verify_gcs_original_preservation.py -v` |
| 실행 ID | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R4` |
| 성공 기준 | 모든 검사 통과, 보존된 삭제 원본의 generation 변경도 실패 처리 |

## 관측 결과

2026-10-17 01:46 KST, 로컬 Python 검사 **10/10 통과**, 실패 0건. 이전 원장과 같은 바이트라도 보존본 generation이 바뀐 경우와 삭제된 원본의 보존본 generation이 바뀐 경우를 각각 실패로 판정했습니다.
