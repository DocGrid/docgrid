# RAG claim 통합 시험의 Context 간 Worker 경합 수정 검증

**원인은 Spring 테스트 Context 캐시에 남은 다른 시험 Context의 `RagJobWorker`였습니다. 이 Worker가 공용 test 스키마를 1초마다 polling하며 시험이 만든 작업을 먼저 claim했습니다. 경합 창을 넓힌 강제 조건에서 수정 전에는 20회 중 20회 실패했고, 수정 후에는 20회 모두 통과했습니다. 수정 후 전체 백엔드 2차 빌드는 1,501건 중 실패·오류 0건이었습니다.**

- 이슈: [#520](https://github.com/DocGrid/docgrid/issues/520) (발견: PR [#519](https://github.com/DocGrid/docgrid/pull/519) CI)
- 시험 파일: `backend/src/test/java/com/opensource/docgrid/domain/rag/integration/RagResponseClaimIntegrationTest.java`
- 운영 코드 변경: 없음

## 1. 원인

PR #519 CI [run 38869352820](https://github.com/DocGrid/docgrid/actions/runs/38869352820) attempt 1에서 `findNextUnclaimedProcessingForUpdate_skipsLockedRow` 1건만 실패했다. 138행 `rowLocked.await(10s)`가 false였다. 같은 JVM에서 바로 앞에 `RagJobWorkerConcurrentQueueIntegrationTest`와 `RagJobWorkerIntegrationTest`가 실행됐다. 두 클래스는 실제 Worker가 켜진 기본 Context를 쓴다. 이 클래스의 Context가 뜬 지 51ms 뒤, 이름이 같은 `rag-worker-job-1` 스레드 두 개가 Ollama 호출 실패를 남기고 이 시험의 queryId 5·6을 fallback으로 처리했다. 이름이 같은 스레드가 둘이라는 점에서 서로 다른 Context가 각각 하나씩 가져간 것으로 본다. 원문은 [CI 발췌](evidence/ci-519-attempt1.txt)에 있다.

이 클래스는 `@MockitoBean RagJobWorker`로 **자기 Context의 Worker만** 막는다(PR #515, `dbeefd4`). 캐시에 남은 다른 Context의 Worker는 같은 test 스키마의 `status=PROCESSING` 행을 같은 쿼리로 claim한다. 외부 Worker가 먼저 가져가면 첫 트랜잭션이 잠글 행이 없어 `orElseThrow`로 끝나고, latch가 내려가지 않는다. 같은 클래스의 `claim_allowsExactlyOneConcurrentOwner`도 같은 이유로 두 claim이 모두 빈 결과가 될 수 있다.

## 2. 수정

이 클래스에만 전용 스키마 `<TEST_DB_SCHEMA>_rag_claim`(기본 `docgrid_test_rag_claim`)을 준다. `application-test.yml`의 접속 URL과 Flyway 설정은 모두 `TEST_DB_SCHEMA`를 따른다. 그래서 `@DynamicPropertySource`로 이 값 하나만 바꾼다. 다른 Context의 Worker는 이 스키마를 보지 않는다. 자기 Context의 Worker는 기존처럼 mock으로 막는다.

| 검토한 방법 | 판단 |
| --- | --- |
| 이 클래스 전용 스키마 (채택) | 시험 코드만 바꾼다. 외부 Worker와 다른 시험의 잔여 행 모두에서 격리된다. 새 스키마 마이그레이션 53건은 0.4~0.5초 걸렸다 |
| test 프로필에서 RAG Worker 끄기 | `RagJobWorker`에 조건부 설정을 추가해야 한다(운영 코드). 실제 Worker가 필요한 두 시험은 다시 켜고 Context를 닫아야 한다 |
| 시험 데이터 구분 (lease를 미래로 둔 행 등) | 외부 Worker와 같은 쿼리를 쓰므로 행만으로는 구분되지 않는다. 시험 대상을 "미선점 행"에서 "만료된 lease 행"으로 바꾸게 된다 |

## 3. 환경

| 항목 | 값 |
| --- | --- |
| 실행 위치 | 로컬 macOS arm64, OpenJDK 17.0.18 |
| 기준 커밋 | develop `37b08a4`(#519 병합 후). 수정은 커밋 전 작업 트리에서 실행 |
| 실행 시각 | 2026-10-16 16:59~17:14 KST |
| DB | 새 격리 `pgvector/pgvector:0.8.1-pg17`(PostgreSQL 17.8, pgvector 0.8.1)·`valkey/valkey:9.1-alpine` 컨테이너. 사용자 로컬 DB(55432)는 쓰지 않음 |
| 그 외 | CI처럼 Ollama 없음. `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3`, JWT는 CI 시험용 값 |
| 시험 파일 SHA-256 | 수정 전 `ccaeeea2…`(develop 원본), 수정 후 `755468aa…` |

## 4. 실행 결과

모든 반복 실행은 같은 JVM에서 `RagJobWorkerConcurrentQueueIntegrationTest` → `RagJobWorkerIntegrationTest` → 이 클래스 순서로 돌렸다(CI와 같다). 반복은 두 시험의 `@Test`를 임시로 `@RepeatedTest(N)`으로 바꿨다. forced는 작업 생성 직후 `Thread.sleep(1500)`을 임시로 넣어 경합 창을 polling 주기(1초)보다 넓힌 조건이다. 임시 변경은 실행 뒤 되돌렸다.

| 실행 ID | 조건 | 시험 파일 | 실행·실패 | 외부 Worker 로그 | 판정 |
| --- | --- | --- | --- | --- | --- |
| forced-single-A | forced 1회, `RagJobWorkerIntegrationTest` 뒤 | 수정 전 | 2·1 | 4줄 (queryId 9·10) | CI와 같은 단언에서 실패. [발췌](evidence/forced-window-single.txt) |
| forced-single-B | forced 1회, 이 클래스 단독 | 수정 전 | 2·0 | 0 | 다른 Context가 없으면 통과 |
| forced-single-fix | forced 1회, `RagJobWorkerIntegrationTest` 뒤 | 수정 후 | 2·0 | 0 | 통과 |
| forced-before-10 | forced, 각 10회 반복 | 수정 전 | 20·**20** | 60줄 | 두 시험 모두 매번 실패 |
| forced-after-10 | forced, 각 10회 반복 | 수정 후 | 20·0 | 0 | 통과 |
| natural-before-200 | 각 200회 반복 | 수정 전 | 400·1 | 4줄 | claim 시험 1회 실패(성공 claim 0건) |
| natural-before-500 | 각 500회 반복 | 수정 전 | 1,000·0 | 0 | 이번 회차는 경합이 생기지 않음 |
| natural-after-500 | 각 500회 반복 | 수정 후 | 1,000·0 | 0 | 통과 |
| [full-build-01](full-build-01/) | CI와 같은 `clean build` | 수정 후 | 1,501·1 (건너뜀 5) | — | RAG claim 2건 통과. 무관한 STOMP 시험 1건 실패(5절) |
| [full-build-02](full-build-02/) | 새 컨테이너로 같은 `clean build` | 수정 후 | 1,501·0 (건너뜀 5) | — | **통과.** 251개 묶음, 1분 29초 |

- 반복 실행 요약과 실패 위치는 [repeat-summary.txt](evidence/repeat-summary.txt)에 있다. 줄 번호는 임시 줄 때문에 밀렸다. import 1줄과 sleep 줄을 빼면 원본의 138행(`rowLocked.await`)과 170행(claim 성공 1건 단언)이다.
- natural 조건의 실패율은 낮다. 수정 전에도 1,400회 중 1회였다. 차이는 forced 조건에서 결정적으로 드러난다.
- 건너뜀 5건은 대시보드 A/B 통합 2건과 HA probe PostgreSQL 통합 3건이다. 전체 시험 수 1,501건은 #519 CI 실행과 같다.

**명령** (저장소 루트에서 실행. 수정 전 조건은 develop `37b08a4`의 시험 파일로 실행)

```bash
# DB_PORT·REDIS_HOST·REDIS_PORT·JWT_SECRET은 격리 컨테이너 값으로 export했다.
docs/test-results/rag-claim-test-isolation-20261016/scripts/repeat-claim-test.sh 10 forced
```

```bash
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3 ./backend/gradlew -p backend clean build --no-daemon --console=plain
```

반복 스크립트는 [scripts/repeat-claim-test.sh](scripts/repeat-claim-test.sh)에 있다.

## 5. 해석 범위

- 바꾼 것은 이 시험 클래스의 접속 스키마 하나다. 다른 Context의 Worker는 그대로 공용 test 스키마를 polling한다. 앞으로 `PROCESSING` RAG 작업을 만들고 claim 순서를 단언하는 시험을 추가하면 같은 격리가 필요하다.
- 로컬에서 실행하면 `<TEST_DB_SCHEMA>_rag_claim` 스키마가 남는다. 다음 실행은 이 스키마를 Flyway로 검증해 다시 쓴다.
- full-build-01에서 `StompSessionLifecycleIntegrationTest`의 "연결 뒤 JWT가 만료되면 기존 구독 세션을 종료한다"가 `awaitSubscription`(241행)에서 3초 timeout으로 실패했다. 이 시험은 수명 1초 JWT를 쓴다. JWT `exp`가 초 단위로 잘리므로 남은 수명은 0~1초다. 100ms 주기의 재검증이 구독 확인보다 먼저 세션을 닫을 수 있다. 이번 변경과 다른 Context·도메인의 문제라 이번 범위에서 고치지 않는다. 새 컨테이너로 다시 실행한 full-build-02에서는 통과했다.
- GitHub Actions에서의 재실행은 PR CI로 확인한다.

## 6. 정리

- 이번 시험에 쓴 격리 컨테이너 `docgrid-ragflaky-pg`·`docgrid-ragflaky-valkey`는 `docker rm -f -v`로 삭제했다. 기존 컨테이너와 사용자 DB는 건드리지 않았다.
