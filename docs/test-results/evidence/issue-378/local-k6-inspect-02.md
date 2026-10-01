# issue378-k6-inspect-20261002-02 — 공식 k6 런타임 재검사

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 Docker, 공식 `grafana/k6:2.3.0` 이미지 |
| 목적 | 시스템 환경 변수와 합성 토큰 파일을 사용한 스크립트 초기화 |
| 명령 | `k6 inspect --include-system-env-vars k6_dashboard_load.js` |
| 성공 기준 | 종료 코드 0, VU 수 1·최대 실행 시간 70초·연결/오류/시계 지표 파싱 |
| 결과 | **통과**, 종료 코드 0. `per-vu-iterations`, VU 1, `maxDuration=1m10s` 확인 |
| 해석 | 공식 k6 2.3.0에서 초기화·옵션 파싱 확인. 실제 네트워크/브로커 부하는 실행하지 않음 |
