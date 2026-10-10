# 고정 경로 첫 실행 — `rag473-crossnode-r1`

- 목적: 앱 B에 WebSocket을 고정하고 HTTP 검색은 A로 보내 Worker 실제 실행 노드를 확인한다.
- 위치·시각: 로컬 전용 IAP SSH 포워드→앱 A/B, 2026-10-10 약 05:47 KST.
- 방법: B `/ws/websocket` 인증·개인 구독, A `/search` 요청. 첫 직접 WSS Upgrade는 공개 Vercel Origin을 직접 넣어 403이었다. 앱이 허용하는 로컬 테스트 Origin으로 바꿔 재실행했다. 운영 CORS 설정은 바꾸지 않았다.
- 관측: query #81 `PROCESSING→FAILED`, B 소켓 `MESSAGE=1`; 하지만 RAG fallback 로그는 **B**에만 1건.
- 판정: 메시지는 받았지만 Worker와 소켓이 모두 B라 **교차 노드 증거로 세지 않는다**. Origin 403은 테스트 클라이언트 조건 오류로 별도 보존한다.
