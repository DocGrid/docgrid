# fix457-promql-attempt-01 — 합성 Fixture 첫 시도

- 기록 시각: 2026-10-10 02:12 KST. 실행 당시 ID와 정확한 시각을 사전 기록하지 못해 이 ID는 사후 부여했다.
- 목적·위치: 로컬 Docker의 기존 `prom/prometheus:v3.5.5` 이미지에서 `promtool test rules`로 단발 오류 쿼리를 검증.
- 명령: `promtool test rules monitoring/grafana/tests/ha_dashboard_http_classes.yml` (읽기 전용 마운트 컨테이너).
- 기대: 합성 Counter 한 표본을 새 쿼리가 1로 유지.
- 결과: **실패 2건**. `rSparse` 500·결과 불명 기대 1에 대해 실제 0. 나머지 기존 테스트는 실패로 보고되지 않았다.
- 다음 조치: 표본 평가 시각이 맞는지 별도 재시험. 이 단계에서는 제품 쿼리 오류인지 Fixture 문제인지 확정하지 않았다.
