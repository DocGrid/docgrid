# 공개 인증 WebSocket 재시험 — `rag473-public-ws-r1`

- 목적: A/B Fix 배포 후 공개 진입점을 거쳐 개인 알림 프레임이 실제 도착하는지 확인한다.
- 위치·시각: 로컬 Node → 공개 HTTPS/WSS → 앱 A/B, 2026-10-10 약 05:45 KST.
- 방법: 보호된 시험 JWT로 `CONNECT` → `/user/queue/rag-answer` 구독 → 1초 뒤 공개 검색 HTTP POST → 프레임 건수와 HTTP 최종 상태 분리 대조. 토큰·이메일·본문은 기록하지 않음.
- 성공 기준: `CONNECTED`, 검색 200, `MESSAGE` 1건 이상.

| 관측 | 결과 | 해석 |
| --- | --- | --- |
| 검색 query #80 | HTTP 200, RAG `PROCESSING→FAILED` | 검색 경로 동작, 답변 생성은 여전히 실패 |
| 인증 소켓 | `CONNECTED=true`, SUBSCRIBE 전송, `MESSAGE=1`, 약 5.6초 | 알림 프레임 실제 수신 통과. receipt는 0으로, 구독 ACK 별도 증거는 없음 |
| Worker 로그·설정 점검 | 앱 A에서 query #80 fallback 1건·오류 코드 `RAG-001`; A/B 모두 `OLLAMA_SERVER_URL` 미설정으로 기본 localhost 사용, localhost 11434 포트 닫힘; 최근 Ollama 호출 실패 로그 A 5·B 2건 | 이 환경의 RAG 답변 실패는 Ollama 서비스 미배포와 일치. 알림 성공과 답변 성공을 섞지 않음 |

이 실행의 WSS 백엔드가 A인지 B인지는 공개 진입점에서 기록하지 않았다. 교차 노드 증거는 별도 고정 경로 시험으로 남긴다.
