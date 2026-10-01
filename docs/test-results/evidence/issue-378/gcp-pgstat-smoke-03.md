# issue378-pgstat-smoke-03 — primary 가드 포함 실노드 검증

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 수집기 → 기존 GCP OpenSQL primary(읽기 전용) |
| 목적 | 스냅샷 전에 `pg_is_in_recovery()=false`를 확인하고 비식별 통계 생성 |
| 명령 | `python3 scripts/opensql/dashboard_pgstat_snapshot.py --instance <DB 노드> --project <프로젝트> --database <앱 DB> --output <새 회차 파일>` |
| 성공 기준 | 대상 노드 primary, 종료 코드 0, 종류별 누적 호출·실행 시간 기록 |
| 결과 | **통과**, 종료 코드 0, primary 확인 후 역할 조회와 primary 확인 2개 종류 수집 |
| 해석 | 이후 부하 회차 전후의 동일 노드·동일 DB 누적값 차이를 계산할 수 있음. 이 smoke 자체는 부하 성능 수치가 아님 |
