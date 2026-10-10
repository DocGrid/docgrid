# HA probe 멱등 경합 — 미커밋 승자 대기 후 재조회

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-idem-pg-20261010-04`, 2026-10-10 11:34 KST |
| 위치·리비전 | 로컬 루프백 전용 일회성 PostgreSQL 16; `db4c220` 기준 작업 브랜치의 미커밋 변경 |
| 목적 | 두 연결이 같은 ID로 충돌할 때 패배한 연결이 미커밋 승자의 커밋을 기다린 후, **같은 READ COMMITTED 트랜잭션**의 다음 SELECT에서 승자 행을 읽는지 확인 |
| 절차 | PostgreSQL 16 컨테이너에 V45 적용 → 승자 INSERT를 미커밋으로 유지 → 패자 INSERT의 `pg_stat_activity.wait_event_type = Lock` 확인 → 승자 커밋 → 패자 INSERT 영향 행 0·후속 SELECT payload 확인 → Gradle JUnit 결과 및 잔여 행 확인 |
| 성공 기준 | 실제 잠금 대기 관측, 패자 INSERT 0행, 후속 SELECT 기존 payload, JUnit 실패·건너뜀 0, 시험 후 잔여 0행 |
| 관측 | V45 `CREATE TABLE` 성공; JUnit **3건 통과·실패 0·건너뜀 0**; 시험 후 잔여 **0행**; 명령 종료 코드 **0** |
| 정리 | 시험 컨테이너 자동 제거, 실행 중 시험 컨테이너 0개 확인. GCP VM·운영 DB·앱 A/B 변경 없음 |

JUnit 3건은 기존 동시 INSERT, 앞선 커밋 후 같은 트랜잭션 재조회, 이번 미커밋 승자와의 경합을 각각 검증한다. 증거는 Gradle 종료 코드와 JUnit XML의 tests=3/skipped=0/failures=0/errors=0, PostgreSQL 잔여 행 조회다. XML에는 로컬 컴퓨터 이름이 포함될 수 있어 원본을 저장소에 넣지 않았다.

이 결과는 **로컬 PostgreSQL의 SQL 계약**이다. 앱 A/B의 HTTP 동시성, GCP 배포 JAR, primary VM 상실과 재전송은 시험하지 않았다. 전체 백엔드 테스트도 앞선 로컬 서비스 DB 연결 실패 기록을 통과로 바꾸지 않는다.
