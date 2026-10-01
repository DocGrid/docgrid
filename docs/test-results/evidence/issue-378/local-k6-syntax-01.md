# issue378-k6-syntax-20261002-01 — k6 스크립트 구문 검사

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 `test/378` 작업 공간 |
| 목적 | STOMP 구독 부하 JavaScript가 문법적으로 파싱되는지 확인 |
| 명령 | `node --input-type=module --check < scripts/opensql/k6_dashboard_load.js` |
| 성공 기준 | 종료 코드 0 |
| 결과 | **통과**, 종료 코드 0, 구문 오류 0건 |
| 해석 | Node 구문 검사만 실행. k6 런타임·실제 STOMP 연결 검증은 아님 |
