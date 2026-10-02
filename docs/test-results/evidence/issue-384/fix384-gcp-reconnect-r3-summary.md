# fix384-gcp-reconnect-r3

- 시각: 2026-10-02 15:37:26~15:39:56 KST (UTC+09:00)
- 실행 위치: 로컬 관측기 → SSH 터널 → GCP 백엔드 B
- 목적: B 재시작 중 새 구독·요약·메시지까지 복구
- 방법: `dashboard_frontend_reconnect.mjs` + B fixture 중지/재시작
- 성공 기준: 새 CONNECT/SUBSCRIBE, HTTP 200, 새 MESSAGE, exit 0
- 관측 결과: LIVE 2회, snapshot 68건 모두 200, MESSAGE 68건, 새 메시지 1건, 복구 122,116ms, exit 0
- 해석: PASS. 수치에는 의도적인 중단·JVM 기동·재시도 대기가 포함됨.
- 정리: B fixture 종료·시험 계정 삭제.
- 원본 허용 목록 이벤트: [`fix384-gcp-reconnect-r3.jsonl`](./fix384-gcp-reconnect-r3.jsonl)
