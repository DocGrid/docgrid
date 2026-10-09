# HA 대시보드 변경 범위·PR 제출 전 검증 — ha-dashboard-pr-review-20261009-01

| 항목 | 기록 |
| --- | --- |
| 실행 시각 | 2026-10-09 15:43 KST |
| 목적·위치 | 로컬 분리 작업 트리 `test/448`에서 PR 전체 범위, 설정 구문, 시험 증거 및 비밀정보 혼입 여부를 확인 |
| 기준 | 원격 `develop`의 `6566da5`와 기존 `test/448` 커밋 3개에 이 작업의 추가 변경을 합친 범위. GCP 장애를 새로 주입하지 않음 |
| 통과 기준 | 관련 검사 전부 성공, 압축 증거 복원 가능, 공개 문서·설정에 비밀값·사설 주소·개인 경로 없음, 의도하지 않은 파일 스테이징 없음 |

| 실행 위치·명령 또는 절차 | 관측 결과 | 해석 |
| --- | --- | --- |
| 로컬: `python3 -m unittest discover -s scripts/opensql -p 'test_*.py'` | **134/134 통과** | HA 계측·원장·기존 OpenSQL 도구 회귀 검사 |
| 로컬: `python3 monitoring/grafana/tests/test_ha_dashboard.py` | **11/11 통과** | 누적 카드·상태 타임라인·접근 경계의 정적 검사 |
| 로컬: `python3 monitoring/grafana/validate_dashboard.py monitoring/grafana/dashboards/docgrid-operations.json` | **36패널·32 PromQL, 성공** | 기존 운영 Dashboard 검사도 유지 |
| 로컬: JSON 파싱, Ruby YAML 파싱, `gzip -t`, `git -c core.whitespace=cr-at-eol diff --cached --check` | **모두 통과** | 대시보드·Blackbox 예시·압축 증거·공백 오류 확인. 원본 1초 CSV의 CRLF는 줄 끝 문자로 취급 |
| 로컬: 공개 문서·Dashboard·Blackbox 설정의 개인 경로, 사설 IPv4, JWT·토큰·개인키 패턴 검색 | **탐지 0파일** | 패턴 검사 결과이며 모든 종류의 정보 유출을 수학적으로 증명하는 것은 아님 |
| 로컬: 압축 이벤트·DB 대조 파일의 같은 패턴 검사 | **탐지 0파일** | 게시할 압축 증거의 비식별 검사 |

기존 장애 보고서는 첫 실행 당시 **8패널**과 이후 확장한 **18패널**을 명시적으로 구분하도록 보완했다. 첫 실행의 OpenProxy·primary 장애 수치를 새 상태코드 카드에서 재측정했다고 주장하지 않는다. 추가 시험의 403 6건과 정상 기준선 201 1,801건도 서로 다른 실행 ID·파일로 유지한다. 이 검사는 저장소와 기존 증거의 PR 준비 확인이며, 새 GCP 장애 시험이나 CI 결과를 대신하지 않는다.

첫 스테이징 후 기본 `git diff --cached --check`는 1초 CSV의 **CRLF 62행**을 trailing whitespace로 보고 종료 코드 2를 냈다. 수집된 원본 바이트와 문서의 SHA-256을 보존하기 위해 CSV를 다시 쓰지 않고, Git의 `cr-at-eol` 옵션으로 **실제 줄 끝 공백 오류 0건**을 재확인했다. 이 실패·재검사 경로를 정상 통과 결과와 구분한다.
