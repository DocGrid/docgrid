# issue378-revocation-unit-20261002-01 — 회수 시각 분류 첫 시도

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 `scripts/opensql` |
| 목적 | HTTP 200 이후 새 발행과 이전 발행의 늦은 수신 구분 |
| 명령 | `python3 -m unittest -v test_websocket_dashboard_revocation.py` |
| 성공 기준 | 경계 분류 시험 4건 통과 |
| 결과 | **실패**, 시험 파일 import 단계에서 로컬 Python에 `websockets` 패키지가 없음 |
| 해석 | 분류 로직의 실패가 아닌 선택적 클라우드 실행 의존성 문제. import를 네트워크 실행 함수 안으로 옮긴 뒤 별도 재실행 |
