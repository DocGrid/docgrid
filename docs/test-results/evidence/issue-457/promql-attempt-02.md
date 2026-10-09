# fix457-promql-attempt-02 — 합성 평가 시각 조정

- 기록 시각: 2026-10-10 02:12 KST. 실행 당시 ID와 정확한 시각을 사전 기록하지 못해 이 ID는 사후 부여했다.
- 목적·위치: 로컬 Docker Prometheus v3.5.5에서 합성 표본의 평가 시각을 500=10초·결과 불명=20초로 맞춤.
- 명령: `promtool test rules monitoring/grafana/tests/ha_dashboard_http_classes.yml` (읽기 전용 마운트 컨테이너).
- 기대: 시각을 맞추면 두 Counter가 1로 평가.
- 결과: **실패 2건**. 두 기대값 모두 여전히 실제 0.
- 다음 조치: 누적 계산 이전의 원시 시계열 존재 여부를 직접 테스트.
