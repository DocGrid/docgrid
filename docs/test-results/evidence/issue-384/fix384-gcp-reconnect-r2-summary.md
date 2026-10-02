# fix384-gcp-reconnect-r2

- 시각: 2026-10-02 15:31:02~15:33:36 KST (UTC+09:00)
- 실행 위치: 로컬 관측기 → SSH 터널 → GCP 백엔드 B
- 목적: B 재시작 후 같은 프런트엔드 제어기의 재구독 확인
- 방법: `dashboard_frontend_reconnect.mjs` + B fixture 중지/재시작
- 성공 기준: 새 CONNECT/SUBSCRIBE, HTTP 200, 새 MESSAGE, 관측기 exit 0
- 관측 결과: LIVE 2회, MESSAGE 81건, 복구 121,426ms, 판정 이벤트 PASS이나 종료 뒤 비동기 로그 쓰기 `EBADF`로 exit 1
- 해석: 제품 복구는 보였지만 하네스 FAIL. 파일 종료 가드를 추가하고 r3로 재실행.
- 정리: B fixture 종료·시험 계정 삭제.
- 원본 허용 목록 이벤트: [`fix384-gcp-reconnect-r2.jsonl`](./fix384-gcp-reconnect-r2.jsonl)
