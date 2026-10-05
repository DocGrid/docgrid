# 기본 전체 테스트 격리 DB 실행: 동시성 2건 실패

- 실행 ID: `outbox439-local-06`
- 완료 시각: 2026-10-06 **01:29 KST** (JVM 종료 로그 기준), 빌드 **1분 20초**
- 목적: 기본 Gradle 테스트가 새 클라우드 전용 테스트를 포함하지 않고 기존 로컬 기능을 회귀 검증하는지 확인
- 위치: 루프백 전용 일회용 PostgreSQL 17/pgvector + 기존 로컬 Valkey, 로컬 Java 17. GCP DB 접속 없음
- 성공 기준: 기본 전체 테스트 실패 0건, 종료 후 전용 DB 컨테이너 제거

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 일회용 PostgreSQL 컨테이너를 **55432**에 생성, SQL 준비 완료 후 `CREATE EXTENSION vector` | 시험 전용 DB만 준비 | 개발 DB·GCP DB를 건드리지 않음 |
| `./backend/gradlew -p backend test --rerun-tasks --console=plain --no-daemon` | **1,360건**, 실패 **2건**, 건너뜀 **2건**, 통과 **1,356건**. `BUILD FAILED` | 기본 전체 회귀는 이 실행에서 미통과 |
| 실패 이름과 단계 분리 | 기존 `DocumentIndexingFailureIntegrationTest`의 완료/실패 동시 요청 1건은 `PessimisticLockingFailureException`. 기존 `RagResponseClaimIntegrationTest`의 미잠금 행 선택 1건은 `NoSuchElementException` | 이번 Outbox 변경 파일과 직접 겹치지 않는 동시성 경로. 단순 환경/스케줄링 문제인지 코드 결함인지는 이 실행만으로 확정 불가 |
| 종료 trap 확인 | 일회용 DB 컨테이너 **0개** | 시험 자원 제거 |

실패를 숨기거나 통과로 바꾸지 않는다. 다음 실행에서는 두 실패 클래스만 새 DB에서 재실행하고, 별도의 새 DB에서 전체 테스트를 다시 확인한다. 원본 XML에는 로컬 식별자가 포함될 수 있어 비식별 요약만 커밋했다.
