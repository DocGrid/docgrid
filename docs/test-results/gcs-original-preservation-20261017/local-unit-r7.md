# generation 지정 다운로드 회귀 시험 · R7

| 항목 | 값 |
| --- | --- |
| 목적 | 증분 감사 규칙과 다운로드 명령의 generation 고정 확인 |
| 실행 위치 | 로컬 작업 사본, GCP 미사용 |
| 방법 | `python3 -m unittest -v test_verify_gcs_original_preservation.py` |
| 실행 ID | `GCS-ORIGINAL-LOCAL-UNIT-20261017-R7` |
| 성공 기준 | 15개 단위 시험 모두 통과 |
| 시각 | 2026-10-17 01:57~01:58 KST |

## 관측

**15/15개 통과**, 실행 0.017초. 다운로드 URI에 목록에서 얻은 generation을 붙이는 경로까지 확인했다. CLI의 실제 GCS 읽기는 [별도 1건 시험](pinned-generation-read-r1.md)에 기록했다.
