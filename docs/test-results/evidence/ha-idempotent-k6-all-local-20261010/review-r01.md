# 전체 ID k6 재전송 — PR 전 독립 재검증 r01

- 실행 ID: `review-20261010-r1`
- 실행 시각: 2026-10-10 21:46 KST 전후 (UTC 12:46)
- 위치: 로컬 분리 작업 공간, `origin/develop` `61515ea238f4e5f45606c1598f1d1e21af6efadc` 기준
- 목적: 기존 선택적 재전송 회귀와 새 전체-ID 재전송 도구의 구문·변경 범위 확인
- 성공 기준: OpenSQL 전체 회귀 실패 0건, 셸·JS·Python 구문 및 Git 공백 오류 0건, 변경 파일에 실제 자격증명·내부 주소 없음

| 검사 | 명령 또는 방법 | 관측 결과 | 판정 |
| --- | --- | --- | --- |
| OpenSQL 전체 회귀 | `PYTHONPATH=scripts/opensql python3 -m unittest discover -s scripts/opensql -p 'test_*.py' -q` | 149개 실행, 실패 0·오류 0, 약 3.9초 | 통과 |
| 정적 검사 첫 시도 | `bash -n`, `node --check`, `python3 -m py_compile`, `git diff --check` | Python 바이트코드 캐시 임시 파일 쓰기가 운영 환경 권한으로 거부됨. 이 시도는 종합 통과로 판정하지 않음 | 환경 오류 |
| 파일 비수정 정적 재검사 | `bash -n`, `node --check`, `ast.parse`, `git diff --check` | 셸·JS·Python 구문 및 공백 오류 0건 | 통과 |
| 민감값 패턴 검사 | 변경된 코드·실행 안내·증거 문서에서 개인 이메일, 사설 IP, 프로젝트 경로, JWT/키 형태 검색 | 일치 파일 0개. 패턴 검사는 모든 비밀정보 부재의 완전한 증명은 아님 | 통과 |

실제 k6 바이너리 실행, 부하 VM 배치, GCP 무장애 HTTP/DB 검증은 이 로컬 기록에 포함되지 않는다. PR·머지 후 별도 실행 ID와 기록으로 수행한다.
