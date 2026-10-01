# issue378-k6-inspect-20261002-03 — 누락·중복 지표 추가 후 공식 런타임 검사

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 Docker, 공식 `grafana/k6:2.3.0` 이미지 |
| 목적 | 수신 순번 누락·중복 지표 추가 뒤 스크립트 초기화 확인 |
| 명령 | `k6 inspect --include-system-env-vars k6_dashboard_load.js` |
| 성공 기준 | 종료 코드 0 |
| 결과 | **통과**, 종료 코드 0, VU 1과 `maxDuration=1m10s` 확인 |
| 해석 | 초기화·옵션 파싱 확인. 실제 구독·메시지 수신은 미실행 |
