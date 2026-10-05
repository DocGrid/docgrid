# Outbox DB 원자성 재실행 로그

- 실행 ID: `outbox439-local-03`
- 실행 시각: 2026-10-05 20:57:33–20:57:52 KST
- 환경·리비전: loopback 전용 임시 PostgreSQL 17 + pgvector, `750b0887c5bf02fc0fd862eb224577eccb435ae1`
- 목적: 실패한 첫 시도와 분리해 Outbox 트랜잭션 경계 재검증
- 성공 기준: DB `SELECT 1` 준비 완료, vector 확장 생성, Flyway 및 5개 테스트 통과
- 방법: DB SQL 응답을 확인한 후 `CREATE EXTENSION IF NOT EXISTS vector`, 이어 `./backend/gradlew -p backend test --tests '*SyncTransactionBoundaryIntegrationTest' --rerun-tasks --console=plain --no-daemon`

| 관측 | 숫자·결과 | 해석 |
| --- | --- | --- |
| 임시 DB 준비 | SQL 응답·vector 확장 준비 완료 | 앞선 setup race 제거 |
| Gradle/JUnit | `BUILD SUCCESSFUL`, **5/5 통과**, 실패·건너뜀 0 | 로컬 DB에서 기존 Outbox 원자성 계약 통과 |
| GCP OpenSQL 경로 | **0건 실행** | 제품 클러스터에서 동일한 결과를 보장하지 않음 |
| 정리 | 임시 컨테이너 제거 | 시험 종료 후 남은 전용 컨테이너 0 |

경고·오류: 없음. 재시도 사유는 [첫 실행](outbox439-local-02.md)에 별도로 남겼다. 실제 GCS 호출이나 Dispatcher의 3노드 소비는 이 테스트 범위 밖이다.
