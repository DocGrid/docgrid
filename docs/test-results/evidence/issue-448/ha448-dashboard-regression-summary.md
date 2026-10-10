# HA 대시보드 201 없는 실행 표시 회귀 요약

| 실행 | 목적 | 위치 | 주요 숫자 | 판정 |
| --- | --- | --- | --- | --- |
| [ha448dashunit01](ha448dashunit01.md) | 첫 정적 회귀 | 로컬 | 11/11, 오류 0 | 통과; 시각 정밀도 한계 기록 |
| [ha448dashpromql01](ha448dashpromql01.md) | 403-only 쿼리 첫 검증 | 로컬 Prometheus 3.5.5 | 0·0·6·0, 4/4 | 통과; 임시 픽스처 |
| [ha448dashunit02](ha448dashunit02.md) | 최종 정적 회귀·JSON | 로컬 | 11/11, JSON 통과 | 통과 |
| [ha448dashpromql02](ha448dashpromql02.md) | 커밋 픽스처 403·201·지표 부재 | 로컬 Prometheus 3.5.5 | 7/7 | 통과 |
| [ha448dashlive01](ha448dashlive01.md) | 403-only 실행의 실제 카드 표시 | GCP Grafana | 0·0·6·0 | 통과; 기존 실행 재조회 |

별도의 GCP 장애·부하 시험은 실행하지 않았다. 기존 [403 실행 기록](../ha-dashboard-preview-20261009/run-05-expired-fixture-smoke.md)은 HTTP 403 **6건**, HTTP 201 **0건**과 4xx 원장·Prometheus 합계 일치를 보여준다. 이번 수정본을 실제 Grafana에 가져온 뒤 이 실행을 선택해 카드 **0·0·6·0건**을 확인했다. 비대화식 SSH 접속은 공개키 인증 단계에서 실패했고, Grafana Explore의 첫 과거 조회도 표본을 확인하지 못했다. 이후 대시보드의 정확한 과거 구간 조회로 카드 값을 확인했으며, 실패한 시도를 성공한 조회로 바꾸어 기록하지 않는다.
