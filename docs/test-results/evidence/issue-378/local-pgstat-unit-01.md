# issue378-pgstat-unit-20261002-01 — SQL 통계 분류 첫 단위 검사

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 `scripts/opensql` |
| 목적 | SQL 종류 분류·비식별·중복 통계 행 합산 |
| 명령 | `python3 -m unittest -v test_dashboard_pgstat_snapshot.py` |
| 성공 기준 | 5개 테스트 통과 |
| 결과 | **실패**, 4/5 통과. 정확한 primary 확인 쿼리만 분류하도록 바뀌면서 인라인 주석이 붙은 합성 SQL이 제외됨 |
| 해석 | 실제 수집 오류가 아닌 시험 데이터와 좁아진 분류 규칙 불일치. 합성 비밀은 별도 필드로 옮겨 재검사 |
