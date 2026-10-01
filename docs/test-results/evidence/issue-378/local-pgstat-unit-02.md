# issue378-pgstat-unit-20261002-02 — SQL 통계 분류 재검사

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 `scripts/opensql` |
| 목적 | 좁힌 SQL 분류·비식별·DB 사용자별 행 합산 확인 |
| 명령 | `python3 -m unittest -v test_dashboard_pgstat_snapshot.py` |
| 성공 기준 | 5개 테스트 통과 |
| 결과 | **통과**, 5/5건, 종료 코드 0 |
| 해석 | 정규화 SQL 분류와 원시 SQL 비기록 단위 로직 확인. 실제 수집은 별도 회차 |
