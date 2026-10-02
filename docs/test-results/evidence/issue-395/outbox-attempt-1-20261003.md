# 실행 로그: Outbox 통합 시험 준비 시도 1

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-outbox-prep-01`, 2026-10-03 03시대 KST. 초 단위 시작·종료 시각은 당시 수집하지 못함 |
| 목적·위치 | 실제 PostgreSQL 트랜잭션 실패 주입 시험 · localhost 전용 임시 PostgreSQL 17 |
| 명령 | 임시 DB 생성 후 `./backend/gradlew -p backend test --tests ...EmbeddingJobLeaseRecoveryIntegrationTest --tests ...SyncTransactionBoundaryIntegrationTest --console=plain` |
| 성공 기준 | Flyway 초기화와 두 suite 실행·통과, 임시 DB 정리 |
| 원본 관측 요약 | Flyway `V32__convert_vector_columns_to_pgvector.sql`에서 SQLSTATE **42704**. Outbox 표시 **5 tests, 5 failed**, 본문 진입 전 Context 실패. Gradle 종료 코드 **1** |
| 해석 | 임시 DB에 `vector` 확장을 설치하지 않은 시험 환경 준비 오류. 제품 코드 실패 또는 OpenSQL 장애 결과로 계산하지 않음 |
| 변경·원복 | 임시 컨테이너 자동 삭제 확인. 후속 시도에서 vector 초기화 추가 |
