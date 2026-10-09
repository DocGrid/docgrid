# app465-config-b-r1 — 앱 B 내부 URL 반영

| 항목 | 관측 |
| --- | --- |
| 목적 | A의 정상 복귀 후 앱 B에 같은 CPU 내부 URL 적용 |
| 위치·시각 | GCP 앱 B VM, 2026-10-10 04:39:49–04:40:40 KST |
| 사전 관문 | 서비스 active, 관리 health 200, URL 키 미설정, CPU readiness 200, 앱 A 복구 완료 |
| 명령·방법 | 보호된 환경파일에 `EMBEDDING_SERVER_URL` 한 줄만 추가 → 소유자·0600 유지 → `systemctl restart docgrid` → 관리 health 확인 → 실제 프로세스 환경의 URL 일치·Worker=false 확인 |
| 결과 | health 24번째 확인에서 HTTP 200; URL 반영 yes, Worker false, 파일 mode 600. PASS |
| 실패·정리 | 실패 시 URL 제거·기존 서비스 재시작하도록 구성. 이번에는 롤백 없음. 주소·비밀은 기록하지 않음 |
