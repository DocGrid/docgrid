# 공개 WebSocket 연결 재검증

- 실행 ID: `vercel-backend-wss-20261010-after`
- 실행 시각: 2026-10-10 KST, A/B 배포와 롤백 타이머 해제 후 최종 재실행.
- 위치·대상: 개발 컴퓨터의 WebSocket 클라이언트 → Cloud Run HTTPS/WSS 게이트웨이 → 내부 LB → 새 백엔드.
- 목적·통과 기준: Vercel 정확한 Origin으로 WebSocket Upgrade 101이 5회 연속 성공.
- 명령·절차: Node `ws` 클라이언트에서 실제 Vercel Origin 헤더로 연결·종료를 5회 반복했다.
- 관측: `public_wss_1`부터 `public_wss_5`까지 모두 101; 실패 0건. 수정 전 별도 실행은 403이었다.
- 해석: 공개 WebSocket 네트워크·Origin 핸드셰이크 경로는 연결됐다.
- 한계: 정상 JWT로 STOMP CONNECT·SUBSCRIBE·메시지 수신을 실행한 것은 아니므로 대시보드 사용자 기능 전체를 검증했다고 주장하지 않는다.
