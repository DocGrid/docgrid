# 실행 로그: Worker lease·인덱싱·Outbox 로컬 단위 시험

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-worker-unit-r2`, 2026-10-03 03:40:43~03:40:45 KST |
| 목적·위치 | 장애 전 복구 코드의 로컬 기준선 · 로컬 JVM, DB mock |
| 명령 | `./backend/gradlew -p backend test --tests ...WorkerLeaseRecoverySchedulerTest --tests ...EmbeddingJobLeaseRecoveryServiceTest --tests ...WorkerIndexingPipelineTest --tests ...SyncEventLeaseRecoveryServiceTest --console=plain` |
| 성공 기준 | 4개 suite에서 실패·오류·skipped 모두 0 |
| 원본 관측 요약 | 각각 **4/4, 6/6, 4/4, 2/2**. 합계 **16/16 통과**, 실패·오류·skipped **0**, Gradle 종료 코드 **0** |
| 해석 | mock 기반 제어 흐름 통과. 실제 PostgreSQL·OpenSQL 장애 중 최종 문서 상태는 미검증 |
| 변경·원복 | 로컬 빌드 결과만 생성, GCP 변경 없음. 이전 같은 목적 실행도 16/16 통과 |
