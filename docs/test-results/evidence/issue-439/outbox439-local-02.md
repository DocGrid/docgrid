# Outbox DB 원자성 첫 시도: 환경 준비 실패

- 실행 ID: `outbox439-local-02`
- 실행 시각: 2026-10-05 20:55–20:57 KST (분 단위 기록)
- 환경·리비전: 로컬 임시 PostgreSQL 17 + pgvector 이미지, `750b0887c5bf02fc0fd862eb224577eccb435ae1`
- 목적: `SyncTransactionBoundaryIntegrationTest`의 DB 트랜잭션 원자성 확인
- 성공 기준: pgvector 확장 준비 후 Flyway 통과, 통합 테스트 5건 모두 통과
- 방법: 임시 DB 컨테이너를 시작하고 확장을 생성한 뒤 `./backend/gradlew -p backend test --tests '*SyncTransactionBoundaryIntegrationTest' --rerun-tasks --console=plain --no-daemon`

| 관측 | 숫자·결과 | 해석 |
| --- | --- | --- |
| 준비 단계 | DB가 연결 가능해지기 전에 `CREATE EXTENSION vector`를 시도해 실패 | 시험 환경 준비 경쟁. 제품 코드 결함으로 분류하지 않음 |
| Flyway | `V32`에서 `type "vector" does not exist` | 확장이 없는 DB에 migration을 실행한 결과 |
| JUnit | **5건 모두 테스트 본문 진입 전 실패** | 원자성 기능의 합격·불합격을 이 실행으로 판정할 수 없음 |
| 정리 | 시험 전용 컨테이너 제거 | 다른 DB/VM 설정 변경 없음 |

첫 확장 생성 오류는 Gradle 로그 캡처가 시작되기 전에 발생했다. 따라서 해당 준비 오류의 원문 로그는 보존됐다고 주장하지 않는다. Gradle 실패 로그는 실행별 로컬 비공개 파일로 보존했고, 다음 재실행에서 DB SQL 준비 완료 확인을 명시적으로 추가했다.
