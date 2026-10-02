# 실행 로그: Outbox 트랜잭션 최종 로컬 시험

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-outbox-final-01`, 2026-10-03 03:41:10~03:41:17 KST |
| 목적·위치 | 실패 trigger·commit/rollback·Outbox 상태 정합성 · localhost 전용 임시 PostgreSQL 17 + pgvector |
| 명령 | vector 확장 초기화·합성 JWT 주입 후 `./backend/gradlew -p backend test --tests com.opensource.docgrid.domain.sync.integration.SyncTransactionBoundaryIntegrationTest --console=plain` |
| 성공 기준 | suite 5건 실행, 실패·오류·skipped 0, 임시 DB 종료 |
| 원본 관측 요약 | **5/5 통과**, 실패 **0**, 오류 **0**, skipped **0**, Gradle 종료 코드 **0**. 같은 컨테이너에서 뒤이어 lease 최종 시험 실행 |
| 해석 | 로컬 DB 트랜잭션 경계 통과. GCP primary 장애 중 Outbox 복구 증거는 아님 |
| 변경·원복 | 실제 비밀 미사용. 두 최종 suite 뒤 임시 컨테이너 삭제 확인 |
