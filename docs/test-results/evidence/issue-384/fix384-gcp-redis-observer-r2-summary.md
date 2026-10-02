# fix384-gcp-redis-observer-r2

- 시각: 2026-10-02 15:46:08~15:47:04 KST (UTC+09:00)
- 실행 위치: GCP 내부 관측 VM → 백엔드 B / 공용 Redis
- 목적: Redis 단절이 구독자에게 미치는 직접 영향 확인
- 방법: B 구독 5개 → `systemctl stop redis` → 8초 뒤 start → HTTP 조회
- 성공 기준: 구독 5개 관측, Redis 복구, 백엔드 생존
- 관측 결과: MESSAGE 490건(대부분 중단 전), 소켓 종료·오류 5건, Redis 중단 8초, 복구 후 HTTP 200/문서수 0
- 해석: Redis 장애에 기존 STOMP 연결이 모두 끊김. 490건을 장애 중 지속 수신으로 해석하지 않음.
- 정리: Redis active, B JVM 생존; 클라이언트 재연결은 별도 run.
- 원본 허용 목록 이벤트: [`fix384-gcp-redis-observer-r2.jsonl`](./fix384-gcp-redis-observer-r2.jsonl)
