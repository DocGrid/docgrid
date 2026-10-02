# 실행 로그: lease 전용 태스크 최초 실행

| 필드 | 관측값 |
| --- | --- |
| 실행 ID·시각 | `ha395-lease-initial-01`, 2026-10-03 약 03:39 KST. 초 단위 시작·종료 시각은 수집하지 못함 |
| 목적·위치 | 태그 제외 없이 실제 PostgreSQL lease 동시성 시험 · localhost 임시 PostgreSQL 17 |
| 명령 | `./backend/gradlew -p backend claimConcurrencyTest --tests ...EmbeddingJobLeaseRecoveryIntegrationTest --console=plain` |
| 성공 기준 | 전용 suite 실제 실행, 실패 0, 임시 DB 정리 |
| 원본 관측 요약 | **4/4 통과**, 실패·오류·skipped **0**, Gradle 종료 코드 **0** |
| 해석 | 로컬 PostgreSQL의 lease 후보·중복 회수 방지 경계 통과. OpenSQL failover 결과가 아님 |
| 변경·원복 | 임시 컨테이너 자동 삭제 확인. 정확한 시각과 분리 로그를 얻으려 최종 실행에서 재시험 |
