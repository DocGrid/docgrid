# fix384-gcp-reconnect-r1

- 시각: 2026-10-02 15:28:31~15:30:02 KST (UTC+09:00)
- 실행 위치: 로컬 관측기 → GCP 백엔드 B 직접 IAP 포트
- 목적: 실제 B 소켓에 연결해 장애 전 정상 구독 확인
- 방법: `dashboard_frontend_reconnect.mjs` (보호된 token 파일, 주소 비기록)
- 성공 기준: LIVE ≥1, HTTP 200, MESSAGE ≥1
- 관측 결과: CONNECTING/POLLING 16건, LIVE 0건, 직접 IAP 포트 4003 거부
- 해석: 준비 실패. 제품 복구 판정 제외; SSH 포트 전달로 바꿈.
- 정리: B fixture 별도 정리; 토큰 로그 미기록.
- 원본 허용 목록 이벤트: [`fix384-gcp-reconnect-r1.jsonl`](./fix384-gcp-reconnect-r1.jsonl)
