# issue378-redaction-20261002-01 — k6 샘플 비식별 검사

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 `scripts/opensql` |
| 목적 | 원시 k6 포인트에서 내부 URL·Authorization 태그를 파일 기록 전에 제거 |
| 명령 | `python3 -m unittest -v test_k6_dashboard_capture.py` |
| 성공 기준 | 비밀 태그 제거, 허용되지 않은 지표·비정상 숫자 제외, 3건 통과 |
| 결과 | **통과**, 3/3건, 종료 코드 0 |
| 해석 | 샘플 allowlist 단위 로직 확인. 실제 k6 실행과 파일 파이프 경로는 미검증 |
