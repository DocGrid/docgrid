# issue378-pgstat-smoke-01 — SQL 통계 수집 첫 검증

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 수집기 → 기존 GCP OpenSQL 노드(읽기 전용) |
| 목적 | `pg_stat_statements` 통계를 원시 SQL 비저장 방식으로 수집 가능한지 확인 |
| 명령 | `python3 scripts/opensql/dashboard_pgstat_snapshot.py --instance <DB 노드> --project <프로젝트> --database <앱 DB> --output <회차 파일>` |
| 성공 기준 | 원격 조회 성공, 내부 식별자·SQL 원문 없는 JSON 생성 |
| 결과 | **수집 성공**, 종료 코드 0, 분류된 통계 행 6개. 다만 최초 분류가 권한 조회 외 진단 SQL까지 포함했음 |
| 해석 | 연결·비식별 출력은 확인. 이 회차의 누적 호출 수는 성능 비교 기준으로 사용하지 않음 |
