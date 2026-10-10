# `475-public-origin-01` — 실제 Vercel Origin의 JWT STOMP 접속

- 목적: Origin 없는 Node 시험으로 놓쳤던 브라우저 403 회귀를 막는다.
- 위치·시각: 로컬 합성 클라이언트 → Vercel 로그인·공개 WSS, 2026-10-10 약 06:10 KST. 시작 초 단위 시각은 별도 수집하지 못했다.
- 소스: 앱 A/B `04c9b0c`
- 방법: 시험 계정으로 공개 로그인 후, 실제 Vercel Origin 헤더와 JWT로 WebSocket Upgrade 및 STOMP CONNECT. 계정·토큰은 출력·기록하지 않았다.
- 성공 기준: 로그인 200, Upgrade 101, STOMP CONNECTED.
- 비식별 원본 요약: `{"login":200,"result":"connected","status":101,"opened":true}`
- 해석: 공개 진입점의 인증 WebSocket 연결 통과. 구독·수신은 별도 실행에서 판정했다.
