# 최초 업로드 멱등 처리 검증 요약

2026-10-13 KST, 로컬 작업 브랜치와 일회성 PostgreSQL 17 + pgvector 시험 DB에서 실행했다. 원시 출력은 환경 경로와 내부 식별자가 섞이지 않도록 보존하지 않고, Gradle 종료 코드와 JUnit XML의 건수만 옮겼다. 실제 GCS·Cloud Run 장애 시험은 수행하지 않았다.

| 실행 ID | 목적 | 관측 결과 | 판정 | 원장 |
| --- | --- | --- | --- | --- |
| `upload-idempotency-unit-20261013-01` | 단위 시험 최초 실행 | 캐시 권한 오류, 테스트 0건 | 환경 오류 | [unit-01](unit-01.md) |
| `upload-idempotency-unit-20261013-02` | 단위 시험 재시도 | 3개 클래스, 종료 코드 0 | 통과 | [unit-02](unit-02.md) |
| `upload-idempotency-db-20261013-01` | 멱등 동작 1차 | 4/4 통과 | 통과 | [db-01](db-01.md) |
| `upload-idempotency-regression-20261013-01` | 기존 업로드 회귀 | 7/7 통과 | 통과 | [regression-01](regression-01.md) |
| `upload-idempotency-db-20261013-02` | 오류·재시도 보강 | 6/6 통과 | 통과 | [db-02](db-02.md) |
| `upload-idempotency-db-20261013-03` | DB Rollback 보강 | 7/7 통과 | 통과 | [db-03](db-03.md) |
| `upload-idempotency-final-20261013-01` | 단위·통합 합동 | 24/24 통과 | 통과 | [final-01](final-01.md) |

최종 합동 실행은 기존 기능과 새 기능의 로컬 회귀를 확인한다. 키 있는 요청의 실제 클라우드 장애 중 결과 불명·저장소 고아 객체는 후속 장애 시험과 후보 객체 복구 범위다.
