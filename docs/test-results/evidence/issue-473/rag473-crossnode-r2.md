# Worker A → 소켓 B 교차 실측 — `rag473-crossnode-r2`

- 목적: 앱 A가 RAG를 끝내고 앱 B의 인증 소켓이 통지를 받는지 증명한다.
- 위치·시각: 로컬 전용 IAP SSH 포워드→앱 A/B, 2026-10-10 약 05:48 KST.
- 방법: 소켓 B 고정·검색 HTTP A 고정, 완료 후 A/B journal에서 query ID별 fallback 횟수만 추출.
- 관측: query #82 `PROCESSING→FAILED`, 소켓 B `CONNECTED=true`, `MESSAGE=1`; Worker 완료 fallback은 **A 1건·B 0건**.
- 판정: **A Worker → 공유 Redis 신호 → B 로컬 개인 queue 전달**이 실제 환경에서 관측됐다. 채널 원문·계정·메시지 본문은 기록하지 않았다. 이 한 건으로 모든 전송·Redis 장애의 무손실을 보장하지 않는다.
