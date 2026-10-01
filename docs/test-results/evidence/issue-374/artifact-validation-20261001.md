# 공개 측정 증거 검증 로그 — 2026-10-01

목적: 이슈 #374의 PR #375에 담을 WebSocket 부하 측정 파일이 회차별로 분리돼 있고, 공개본의 숫자가 원본과 일치하며, 알려진 민감정보 패턴이 없는지 확인한다. 실행 위치는 `test/374` 브랜치의 저장소 루트다. 이는 **기존 클라우드 부하 시험의 증거 검증**이지 새 GCP 부하 시험이 아니다.

최종 공개 전 별도 재검증은 [22:46 KST 실행 로그](publication-check-20261001-2246.md)에 기록했다.

| 실행 시각(KST) | 명령·절차 | 수치·결과 | 해석 |
| --- | --- | --- | --- |
| 2026-10-01 22:23 | `python3 -m unittest -v scripts.opensql.test_websocket_dashboard_evidence` | 3개 테스트, 실패 0건, 약 0.006초 | 12개 회차 파일, 10개 앱 지표 파일, 50명 3회 중앙값 대조 통과 |
| 2026-10-01 22:23 | `python3 -m py_compile scripts/opensql/websocket_dashboard_load.py scripts/opensql/websocket_dashboard_metrics.py` | 두 파일 구문 검사 성공 | 클라이언트 코드는 구문상 유효함. `websockets` 패키지가 현재 로컬 환경에 없어 실제 연결 실행은 하지 않음 |
| 2026-10-01 22:23 | `jq -e . runs/*.json metrics/*.jsonl >/dev/null` (실제로는 `evidence/issue-374/` 경로 지정) | JSON 12개·JSONL 10개 파싱 성공 | 공개 원본에 JSON 문법 오류 없음 |
| 2026-10-01 22:23 | 임시 보관 원본과 공개본 22개 파일을 파일명별 `cmp -s` | 불일치 0건 | 공개 JSON/JSONL은 수치 편집 없이 그대로 복사됨 |
| 2026-10-01 22:23 | 공개 보고서·코드·원본에 대해 프로젝트 ID, 내부 IP, JWT 형태, 개인 홈 경로, PEM 키 패턴을 `rg -l`로 검사 | 일치 파일 0개 | 이 패턴의 민감값은 찾지 못함. 모든 비밀 유형을 증명하는 검사는 아니므로 PR 직전 변경 파일을 재검토함 |
| 2026-10-01 22:24 | `git diff --cached --check`와 변경 파일 목록 확인 | 28개 파일, 공백 오류 0건 | 의도한 측정 자료·스크립트만 스테이징됨 |
| 2026-10-01 22:28 | 50명 수정 버전 JVM 덤프의 핵심 프레임을 대조하고 JVM 주소·스레드 ID를 제외한 발췌 생성 | `MessageBroker → SimpleBroker fan-out → outbound 인가 → primary 역할 조회 → PostgreSQL 응답 대기` 순서 확인 | 전체 덤프 대신 분석에 필요한 호출 경로만 공개. 발췌는 원본 전체가 아님 |
| 2026-10-01 22:42 | 원본 JVM 덤프를 PID·tid·nid·16진 주소 치환 후 최초 공개 후보와 `cmp -` 대조 | **원본 1117줄**, 치환 후 불일치 0건, 원래 주소 잔여 0건 | 이후 끝의 빈 줄·공백을 정리해 최종 공개본은 1116줄. 호출 프레임은 보존 |
| 2026-10-01 22:42 | DB 전후 스냅샷 4개에 `jq 'map(del(.server_ip))'` 적용 후 공개본과 `cmp -` 대조 | **4개 일치**, 각 파일 primary 1·standby 2 | 내부 IP만 제외하고 전후 거래 지표를 보존 |
| 2026-10-01 22:42 | `python3 -m unittest -v scripts.opensql.test_websocket_dashboard_evidence` 재실행 | **5개 테스트 통과, 실패 0건** | 기존 3개 검사에 JVM 덤프·DB 스냅샷 무결성 2개를 추가 |
| 2026-10-01 22:42 | 공개 증거·문서에 내부 IP·프로젝트 ID·JWT·이메일·개인 홈 경로 패턴 `rg -l` 검사 | 일치 파일 0개 | 패턴 기반 비식별 검사이며 알려지지 않은 모든 민감값을 배제한다는 보장은 아님 |

## 한계

- 앱 journal 선별 로그는 과거 시험 중 실시간으로 수집해 [보고서](../../gimin-374-stomp-dashboard-outbound-load-20261001.md)에 기록했다. 이번에는 남아 있던 JVM 전체 덤프의 [비식별본](jvm-thread-dump-fix-n50-redacted.txt)과 DB 전후 스냅샷의 [비식별본](db/)도 추가했다. [핵심 근거 로그](key-evidence-logs-20261001.md)는 원본과 계산값을 구분해 인용한다. 삭제된 임시 VM에 다시 접속하거나 새로운 부하를 발생시키지는 않았다.
- 임시 Java publisher·시험용 계정 fixture는 공개하지 않았으므로, 이번에 보관한 Python 클라이언트만으로 당시 합성 시험을 곧바로 다시 실행할 수 없다.
- 외부 접속·부하 재시험, 2백엔드 분산, 관리자 역할 회수 중 부하의 동시 재현은 이 검증 로그의 범위가 아니다.
