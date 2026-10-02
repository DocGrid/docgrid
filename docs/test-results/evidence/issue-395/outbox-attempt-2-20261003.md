# 실행 로그: Outbox 통합 시험 준비 시도 2

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-outbox-prep-02`, 2026-10-03 03시대 KST. 초 단위 시작·종료 시각은 당시 수집하지 못함 |
| 목적·위치 | vector 확장을 넣고 PostgreSQL 트랜잭션 시험 재시도 · localhost 전용 임시 PostgreSQL 17 |
| 명령 | `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public` 후 Gradle `test --tests ...SyncTransactionBoundaryIntegrationTest` 포함 실행 |
| 성공 기준 | Flyway·Spring Context·시험 본문 통과, 임시 DB 정리 |
| 원본 관측 요약 | Flyway 단계는 통과. `PlaceholderResolutionException`에서 합성 시험용 `JWT_SECRET` 미설정 확인. Outbox 표시 **5 tests, 5 failed**, 본문 진입 전 Context 실패. Gradle 종료 코드 **1** |
| 해석 | 시험 설정 누락. 실제 JWT 비밀은 읽거나 사용하지 않음. 제품 코드 결함으로 계산하지 않음 |
| 변경·원복 | 임시 컨테이너 자동 삭제 확인. 후속 시도에서 합성 JWT 시험 키만 주입 |
