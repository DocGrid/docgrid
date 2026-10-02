# fix384-gcp-redis-client-r1

- 시각: 2026-10-02 15:49:48~15:52:48 KST (UTC+09:00)
- 실행 위치: 로컬 관측기 → SSH 터널 → GCP 백엔드 B / Redis
- 목적: 초기 구현에서 Redis 단절 후 복구 여부 확인
- 방법: `dashboard_frontend_reconnect.mjs` + Redis 8초 중지·재시작
- 성공 기준: 180초 안에 재연결·snapshot·새 MESSAGE
- 관측 결과: 초기 LIVE·MESSAGE 55건 뒤 재연결 0건, 180초 시간초과, exit 1
- 해석: FAIL. STOMP ERROR를 영구 중단해 복구하지 않음. 수정 근거.
- 정리: Redis active; 토큰 파일은 추후 삭제.
- 원본 허용 목록 이벤트: [`fix384-gcp-redis-client-r1.jsonl`](./fix384-gcp-redis-client-r1.jsonl)
