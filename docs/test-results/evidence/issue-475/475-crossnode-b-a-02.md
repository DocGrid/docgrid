# `475-crossnode-b-a-02` — A Worker → B 인증 소켓 교차 전달

- 목적: 같은 계정의 B 고정 구독이 A Worker의 완료 알림을 받는지 확인
- 위치·시각: 로컬 IAP SSH 포워드 → GCP 앱 A/B, 2026-10-10 06:12 KST. A 처리 로그 `2026-10-09T21:12:25.238Z` ~ `21:12:25.578Z`.
- 소스: 앱 A/B `04c9b0c`
- 방법: 두 포워드를 앱 포트 `8080`으로 수정. B에서 JWT CONNECT·개인 queue SUBSCRIBE, A로 합성 검색 1건 요청, 양쪽 앱에서 해당 합성 query의 Worker 로그 건수만 확인.
- 성공 기준: A Worker 처리와 B MESSAGE 1건, 중복 수신 0건.
- 비식별 원본 요약: `receiver=B, requestNode=A, connected=true, subscribed=true, messageCount=1, ragStatus=FAILED`. 동일 합성 query에 대한 A 로그는 검색 완료·RAG enqueue·`RAG-001` fallback 3줄, B 처리 로그 0줄이었다.
- 해석: A에서 처리된 결과가 B의 로컬 인증 소켓에 도달했다. 이 한 번의 A→B 성공이 역방향·Redis 장애 중 무손실을 증명하지는 않는다.
