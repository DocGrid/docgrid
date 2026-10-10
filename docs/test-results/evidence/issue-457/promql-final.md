# fix457-promql-final — 희소 Counter 최종 합성 검증

- 기록 시각: 2026-10-10 02:12 KST. 실행 전 이 ID와 성공 기준을 선언했다.
- 목적·위치: 로컬 Docker의 기존 `prom/prometheus:v3.5.5` 이미지, 실행 후 컨테이너 자동 제거.
- 명령: `promtool test rules monitoring/grafana/tests/ha_dashboard_http_classes.yml` (저장소 파일을 읽기 전용 마운트).
- 입력: `rSparse`에 VU 연속 표본, HTTP 500 Counter 1표본, 결과 불명 Counter 1표본. 다른 시험 그룹은 403 전용·201 전용·미존재 실행.
- 성공 기준: 500·결과 불명 누적값 1, 500의 `rate(...[15s])` 결과 없음, 기존 0 대체 기대값 유지.
- 결과: **SUCCESS**. PromQL 기대값 총 12개 통과, 컨테이너 회수.
- 한계: 시작 시각에 한 표본을 둔 합성 입력이다. 실제 장애 실행의 0→1 계단은 별도 GCP 과거 데이터 조회로 검증했다.
