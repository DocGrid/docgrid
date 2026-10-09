# 문서 버전 한정 Worker 단위 검증 — scope467-unit-r1

| 항목 | 기록 |
| --- | --- |
| 시각 | 2026-10-10 04:47~04:49 KST |
| 장소 | 로컬 JVM, 격리된 Mock Repository |
| 대상 | `test/467`, 로컬 작업 트리 |
| 목적 | 시험 문서 버전만 Claim·복구하고 일반 프로필의 필터 설정을 거부하는지 검증 |
| 명령 | `./gradlew test --tests '*EmbeddingJobClaimServiceTest' --tests '*EmbeddingJobRecoveryQueryServiceTest' --tests '*WorkerLeaseRecoverySchedulerTest' --tests '*WorkerExecutionConfigTest' --tests '*IndexingWorkerPropertiesTest' --console=plain` |
| 최종 결과 | 31건 통과, 실패 0건, 건너뜀 0건; 약 3초 |

실행 이력은 덮어쓰지 않고 아래처럼 구분한다.

| 실행 | 결과 | 해석 |
| --- | --- | --- |
| 첫 실행 | 기존·신규 테스트 30건 통과 | 초기 범위 분기와 프로필 차단은 통과했다. 이후 빈 설정값 테스트를 추가했다. |
| 두 번째 실행 | 테스트 코드 컴파일 실패 1건 | Spring `BindResult.orElseThrow`에는 예외 공급자가 필요했다. 제품 코드 오류가 아니라 새 테스트 코드 오류다. |
| 세 번째 실행 | 31건 중 1건 실패 | 빈 값만 있는 PropertySource는 Bean 자체가 바인딩되지 않는다. `enabled=false`를 함께 넣어 실제 설정 구조로 수정했다. |
| 최종 실행 | 31건 통과 | 필터 미설정 기본 동작, 필터 지정 시 Claim·복구 분리, 프로필 거부를 확인했다. |

Gradle의 원본 XML은 로컬 `backend/build/test-results/test/`에 생성됐다. 이 문서에는 호스트명·계정·비밀이 들어갈 수 있는 원본 로그 대신 결과 숫자와 실패 원인만 기록했다. 실제 DB Repository 통합 테스트는 이 실행에 포함되지 않는다.
