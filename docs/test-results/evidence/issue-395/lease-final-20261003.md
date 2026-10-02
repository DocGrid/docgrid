# 실행 로그: Worker lease 최종 로컬 시험

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-lease-final-01`, 2026-10-03 03:41:17~03:41:24 KST |
| 목적·위치 | 실제 PostgreSQL의 만료 lease 후보·동시성·재처리 상태 · localhost 전용 임시 PostgreSQL 17 + pgvector |
| 명령 | `./backend/gradlew -p backend claimConcurrencyTest --tests com.opensource.docgrid.domain.embedding.integration.EmbeddingJobLeaseRecoveryIntegrationTest --console=plain` |
| 성공 기준 | suite 4건 실행, 실패·오류·skipped 0, 임시 DB 종료 |
| 원본 관측 요약 | **4/4 통과**, 실패 **0**, 오류 **0**, skipped **0**, Gradle 종료 코드 **0**. 시험용 컨테이너 **3/3 삭제 확인** |
| 해석 | 로컬 DB lease 회수 경계 통과. GCP primary 장애 중 문서 인덱싱 최종 상태 증거는 아님 |
| 변경·원복 | 합성 DB/JWT만 사용; 임시 컨테이너 삭제 확인; GCP 자원 변경 없음 |
