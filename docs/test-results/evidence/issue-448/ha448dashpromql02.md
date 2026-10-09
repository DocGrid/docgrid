# 403·201·지표 부재 합성 PromQL 재검증 — ha448dashpromql02

- 시각: 2026-10-09 **16:07:00~16:07:12 KST**.
- 위치·개정: 로컬 Docker의 기존 `prom/prometheus:v3.5.5` 이미지, 저장소의 `monitoring/grafana/tests/ha_dashboard_http_classes.yml`.
- 목적: PR에 포함할 픽스처로 세 조건의 카드 계산을 재검증.
- 명령: `docker run --rm --entrypoint=promtool --mount type=bind,src=<저장소>/monitoring/grafana/tests/ha_dashboard_http_classes.yml,dst=/tmp/test.yml,readonly prom/prometheus:v3.5.5 test rules /tmp/test.yml`.
- 성공 기준: 403 전용 **4건**은 0·0·6·0, 201 기준선 **2건**은 2xx 1,801·4xx 0, 지표가 전혀 없는 **1건**은 결과 없음.
- 관측: 합성 판정 **7/7 통과**, `SUCCESS`, 종료 코드 **0**. 컨테이너는 시험 후 제거됐다.
- 해석: 응답 0건과 지표 부재를 구분하는 PromQL 동작은 확인했다. 이 수치는 합성 값이며 새 GCP 부하 시험 결과가 아니다.
