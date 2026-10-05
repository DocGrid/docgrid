# 전체 기본 Gradle 테스트 실행 로그

- 실행 ID: `outbox439-local-04`
- 실행 시각: 2026-10-05 21:02–21:03:38 KST (시작은 분 단위 기록)
- 환경·리비전: 로컬 JVM + loopback 전용 임시 PostgreSQL 17/pgvector, `750b0887c5bf02fc0fd862eb224577eccb435ae1`
- 목적: Outbox 기준선 문서 작성 전 기본 테스트 전체의 회귀 여부 확인
- 성공 기준: Gradle `test` 성공, JUnit 실패·오류 0, 임시 컨테이너 정리
- 방법: SQL 준비 완료와 vector 확장을 확인한 뒤 `./backend/gradlew -p backend test --rerun-tasks --console=plain --no-daemon`

| 관측 | 숫자·결과 | 해석 |
| --- | --- | --- |
| Gradle | `BUILD SUCCESSFUL in 1m 17s` | 기본 `test` 태스크 완료 |
| JUnit XML 합계 | **229 suite, 1,360건: 통과 1,358 / 건너뜀 2 / 실패 0 / 오류 0** | 건너뜀 2건을 통과 건수에 넣지 않음 |
| Outbox 관련 DB 통합 suite | Claim **1/1**, Dispatch 실패·복구 **5/5**, 멱등 동시성 **3/3**, 트랜잭션 경계 **5/5** | 로컬 PostgreSQL의 실제 SQL·트랜잭션은 검증. FileStorageService는 대체 객체라 GCS는 미검증 |
| 태그 범위 | `build.gradle`의 기본 `test` 제외 태그는 실행되지 않음 | 성능·클라우드·일부 동시성 전용 suite까지 전부 검증했다는 뜻은 아님 |
| 정리 | 시험 전용 컨테이너 제거·잔여 0 확인 | 운영 VM/DB를 변경하지 않음 |

JUnit 숫자는 `build/test-results/test/TEST-*.xml`의 `<testsuite>` 합계로 집계했다. 콘솔 원본은 실행별 로컬 비공개 로그에 보존했다. 종료 시 Hikari/JPA/WebSocket 테스트 컨텍스트의 정상 shutdown 로그가 출력됐으며 오류는 없었다. 민감한 로컬 경로는 기록 전에 가렸다.
