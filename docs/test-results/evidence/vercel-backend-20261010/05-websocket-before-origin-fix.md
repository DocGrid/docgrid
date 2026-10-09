# WebSocket Origin 수정 전 기준선

- 실행 ID: `vercel-backend-wss-20261010-before`
- 실행 시각: 2026-10-10 KST. 초 단위 실행 시각은 별도로 보존하지 않았다.
- 위치·대상: 공개 WSS 게이트웨이 → 기존 백엔드 A/B JAR.
- 목적·통과 기준: 실제 Vercel Origin의 WebSocket 연결 가능 여부를 판정한다.
- 명령·절차: `ws` 클라이언트에서 `Origin: https://zippy-lute-8okpbzh.vercel.app`을 보내 WebSocket 핸드셰이크 응답을 확인했다.
- 관측: 기존 백엔드는 HTTP 403으로 핸드셰이크를 거부했다.
- 해석: HTTP 연결만으로 대시보드 WebSocket까지 연결된 것은 아니었다. `CorsConfig.ALLOWED_ORIGINS`에 정확한 공개 Origin을 추가해야 했다.
- 한계: 인증된 STOMP CONNECT 전 단계에서 차단됐으므로 구독·메시지 전송 동작은 이 실행에서 관측하지 못했다.
