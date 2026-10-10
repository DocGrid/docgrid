# HA probe 멱등 재조회 — 실제 PostgreSQL 트랜잭션 검증

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-idem-pg-20261010-03`, 2026-10-10 11:33 KST |
| 위치·리비전 | 로컬 루프백 전용 일회성 PostgreSQL 16; `db4c220` 기준 작업 브랜치의 미커밋 변경 |
| 목적 | `(run_id, request_id)` 충돌 시 `READ COMMITTED` 한 트랜잭션 안의 다음 `SELECT`가 기존 payload를 읽는지 재검증 |
| 절차 | `postgres:16` 일회성 컨테이너 실행 → V45 SQL 적용 → `HA_PROBE_TEST_JDBC_URL`을 로컬 루프백에 한정해 `./backend/gradlew -p backend test --tests '*HaProbeIdempotentPostgresIntegrationTest' --console=plain` 실행 → 시험 행 수 확인 → 컨테이너 중지 |
| 성공 기준 | 기존 커밋 행에 대한 INSERT 영향 행 0, 같은 트랜잭션에서 기존 payload 조회, 다른 payload 충돌 확인, 동일 ID 최종 1행, 시험 후 잔여 0행 |
| 관측 | V45 `CREATE TABLE` 성공, JUnit **2건 통과·실패 0·건너뜀 0**, 시험 후 잔여 **0행**, 명령 종료 코드 **0** |
| 정리 | 시험 컨테이너는 자동 제거됨. GCP VM·운영 DB·앱 A/B는 변경하지 않음 |

두 JUnit 중 한 건은 기존 두 DB 연결의 동시 INSERT 합계가 1행인지 확인하고, 추가한 한 건은 같은 `READ COMMITTED` 트랜잭션 안에서 충돌 INSERT와 후속 SELECT를 확인한다. 이 실행은 **이미 커밋된 승자 행의 재조회**를 증명한다. 패배한 트랜잭션이 다른 미커밋 트랜잭션을 기다린 바로 다음 문장에서 행을 보는 경합 경계, 실제 앱 A/B HTTP 동시 호출, GCP 장애와 재전송 결과는 별도 검증 대상이다.

원본 JUnit XML은 로컬 Gradle 산출물로만 생성했으며, 컴퓨터 이름이 포함될 수 있어 저장소에는 넣지 않았다. 수치는 실행 직후 XML의 tests=2, skipped=0, failures=0, errors=0과 종료 코드·행 수를 확인해 옮겼다.
