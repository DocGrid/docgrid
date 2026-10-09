# `475-crossnode-b-a-01` — 잘못된 관리 포트 연결

- 목적: B 구독·A 검색 고정으로 교차 전달 검증
- 위치·시각: 로컬 IAP SSH 포워드 → GCP 앱 A/B, 2026-10-10 06:11 KST. B 오류 로그 시각 `2026-10-09T21:11:31.722Z`.
- 방법: 두 포워드를 관리용 `8081`에 잘못 연결한 상태에서 B WebSocket Upgrade 시도.
- 성공 기준: B CONNECTED, A 검색, B MESSAGE 1건.
- 비식별 원본 요약: `upgrade_http_500, connected=false, queryCreated=false, messageCount=0`; B 로그 예외는 `No static resource ws/websocket`.
- 해석: 시험 배선 오류다. `8081`은 관리 포트이며 사용자 WebSocket·API는 `8080`이다. 서비스 가용성 실패로 집계하지 않고 터널을 닫은 뒤 새 실행 ID로 재시험했다.
