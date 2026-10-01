# issue378-pgstat-smoke-02 — 좁힌 SQL 분류 실노드 검증

| 항목 | 기록 |
| --- | --- |
| 실행 위치 | 로컬 수집기 → 기존 GCP OpenSQL 노드(읽기 전용) |
| 목적 | 역할 조회·primary 확인만 통계에 남기는지 확인 |
| 명령 | `python3 scripts/opensql/dashboard_pgstat_snapshot.py --instance <DB 노드> --project <프로젝트> --database <앱 DB> --output <새 회차 파일>` |
| 성공 기준 | 종료 코드 0, 공개 가능한 분류별 누적값만 기록 |
| 결과 | **통과**, 종료 코드 0, 분류된 종류 2개(사용자별 역할 조회·primary 확인) |
| 해석 | 수집·분류 가능. 이 버전에는 대상 노드의 primary 상태 확인이 아직 없어 최종 하네스로 쓰지 않음 |
