# HA 대시보드 표시 변경 로컬 검증 — ha-dashboard-preview-20261009-01

| 항목 | 기록 |
| --- | --- |
| 실행 시각 | 2026-10-09 14:09:52 KST |
| 목적 | 기존 Grafana JSON의 상태 타임라인·현재 리더 카드·앱 오류 패널을 정적으로 검증 |
| 위치·기준 | 로컬 분리 작업 트리, `test/448`의 `c794914` 기반 미커밋 변경 |
| 절차 | `python3 -m unittest monitoring/grafana/tests/test_ha_dashboard.py -v`; `python3 -m json.tool monitoring/grafana/dashboards/docgrid-ha-demo.json`; `git diff --check` |
| 통과 기준 | 단위 테스트 전부 통과, JSON 파싱 성공, diff 공백 오류 0건 |
| 관측 결과 | 단위 테스트 **9/9 통과**, JSON 파싱 성공, diff 검사 오류 **0건** |
| 미검증 | 실제 Grafana 가져오기·시각 표시, 설치된 Patroni/Hikari 메트릭·PromQL 실행, OpenProxy 포트 관측, 장애 시연 |

이 기록은 로컬 정적 검증 결과이며 GCP 관측 VM에 적용됐다는 증거가 아니다. 원격 접근에는 별도 승인이 필요한 임시 OS Login 키 등록이 막혀 있어, 원격 결과를 추정해 기록하지 않았다.
