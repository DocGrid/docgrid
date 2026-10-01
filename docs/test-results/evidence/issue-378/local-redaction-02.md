# issue378-redaction-20261002-02 — 누락·중복 지표 추가 후 비식별 재검사

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 `scripts/opensql` |
| 목적 | k6 허용 지표 확장 후에도 내부 URL·토큰 태그가 저장되지 않는지 확인 |
| 명령 | `python3 -m unittest -v test_k6_dashboard_capture.py` |
| 성공 기준 | 3개 시험 통과 |
| 결과 | **통과**, 3/3건, 종료 코드 0 |
| 해석 | Python 샘플 비식별 로직만 확인. 실제 FIFO 스트리밍은 별도 실측 필요 |
