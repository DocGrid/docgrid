# fix457-promql-attempt-03 — 원시 시계열 확인

- 기록 시각: 2026-10-10 02:12 KST. 실행 당시 ID와 정확한 시각을 사전 기록하지 못해 이 ID는 사후 부여했다.
- 목적·위치: 로컬 Docker Prometheus v3.5.5에서 `rSparse` 원시 500 Counter 자체를 조회.
- 명령: `promtool test rules monitoring/grafana/tests/ha_dashboard_http_classes.yml` (원시 Counter 기대값을 추가한 Fixture).
- 기대: 원시 Counter 1, 누적 500 1, 누적 결과 불명 1.
- 결과: **실패 3건**. 원시 Counter 결과가 `nil`이고 두 누적 쿼리가 0. 맨 앞을 누락 표본(`_`)으로 둔 이 Fixture는 이 실행에서 원시 시계열을 만들지 못했다.
- 다음 조치: 한 표본을 Fixture 시작 시각에 두고, 이후에는 표본을 생략해 `rate()`와 `last_over_time()` 차이를 확인.
