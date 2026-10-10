# 응답코드 계측·대시보드 로컬 회귀 — ha-dashboard-code-20261009-01

| 항목 | 기록 |
| --- | --- |
| 실행 날짜·시간대 | 2026-10-09 KST. 정확한 시작·종료 시각은 별도로 보존하지 않아 추정하지 않음 |
| 목적 | k6의 2xx·3xx·4xx·5xx 무태그 Counter, 안전 원장, 최종 Remote Write 대조 및 Grafana 카드·배치가 함께 작동하는지 검사 |
| 위치·기준 | 로컬 `test/448`, 기반 커밋 `c79491408d84` + 이 작업의 미커밋 변경. GCP 앱 JAR 회귀 시험이 아님 |
| 명령 | `python3 -m unittest discover -s scripts/opensql -p 'test_*ha*py'`; `python3 -m unittest discover -s monitoring/grafana/tests -p 'test_ha_dashboard.py'`; `python3 -m json.tool monitoring/grafana/dashboards/docgrid-ha-demo.json`; `git diff --check` |
| 통과 기준 | 관련 Python 시험 모두 성공, JSON 파싱·diff 공백 검사 성공, k6 설치 VM에서 실제 스크립트 파싱 성공 |

## 관측 결과

| 검사 | 결과 | 해석 |
| --- | --- | --- |
| HA 관련 Python 시험 | **85/85 통과**, 실패 0 | 안전 이벤트·원격 합계·원장 재생의 200/201/3xx~5xx 경계를 포함 |
| Grafana 정적 시험 | **11/11 통과**, 실패 0 | 누적 카드 4개, 실행 ID 필터, 역할 색, 타임라인 크기, 프록시 범위, 15초 timeout 쿼리 검사 |
| JSON·diff | 파싱 성공, `git diff --check` 성공 | 저장소 JSON 구문·공백 검사 통과. 실제 Grafana 렌더링의 단독 증거는 아님 |
| GCP 부하 VM의 `k6 inspect -e ...` | 종료 코드 **0** | 설치된 k6가 새 JS를 파싱함. 더미 토큰만 사용하여 HTTP 요청 없음 |

첫 Grafana 정적 시험은 설명문에 ‘결과 불명’ 용어가 빠져 **1건 실패**했다. 설명문을 고치고 재실행하여 11/11 통과했다. 첫 `k6 inspect`는 옵션 대신 프로세스 환경 변수만 주어 필수 `__ENV`가 없다는 파싱 전 오류(종료 코드 107)를 냈다. `-e` 인자로 수정한 재실행은 통과했다. 이 두 실패를 실제 부하 시험 실패나 제품 결함으로 합치지 않는다.

최종 변경 후 핵심 4개 모듈을 다시 돌려 **35/35 통과**했고 JSON 파싱·`git diff --check`도 통과했다. 별도로 로컬 Python 전체 검색을 시도한 결과 처음에는 **134건 중 5건이 샌드박스의 loopback 소켓 바인딩 제한으로 실행 오류**가 났다. 같은 명령을 loopback 사용이 허용되는 환경에서 다시 실행해 **134/134 통과**했다. 최초 제한을 제품 실패로 판정하지 않으며, 재실행 결과로 로컬 전체 Python 검사 통과를 확인했다.

원시 테스트 stdout에는 합성 UUID 등이 포함되어 저장하지 않았다. 이 파일은 검사 건수·오류 종류만 별도로 정리한 안전 요약이다.
