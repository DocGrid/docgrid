# `475-public-rag-01` — 공개 검색 결과 알림

- 목적: 공개 경로에서 JWT STOMP 구독이 실제 검색 완료 알림까지 받는지 확인
- 위치·시각: 로컬 합성 클라이언트 → Vercel·공개 게이트웨이·앱 A/B, 2026-10-10 06:10 KST. 첫 MESSAGE 시각 `2026-10-09T21:10:10.478Z`.
- 소스: 앱·프런트 `04c9b0c`
- 방법: 공개 로그인 → 실제 Origin으로 CONNECT → 개인 `/user/queue/rag-answer` SUBSCRIBE → 합성 검색 1건 → MESSAGE 수와 최종 RAG 상태 조회. JWT·메시지 본문은 기록하지 않았다.
- 성공 기준: 인증·구독 성공, 해당 실행에서 MESSAGE 정확히 1건.
- 비식별 원본 요약: `loginHttp=200, connected=true, subscribed=true, queryCreated=true, messageCount=1, ragStatus=FAILED`.
- 해석: 완료 알림은 수신했다. 답변 생성은 실패해 성공 사례로 계산하지 않는다.
