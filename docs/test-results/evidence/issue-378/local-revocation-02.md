# issue378-revocation-unit-20261002-02 — 회수 시각 분류 재실행

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 `scripts/opensql` |
| 목적 | 회수 전·후 및 경계 시각 분류와 STOMP 다중 프레임 파싱 |
| 명령 | `python3 -m unittest -v test_websocket_dashboard_revocation.py` |
| 성공 기준 | 4개 시험 통과 |
| 결과 | **통과**, 4/4건, 종료 코드 0 |
| 해석 | 시간 경계 분류의 결정적 로컬 테스트. 실제 GCP 권한 회수 결과는 아님 |
