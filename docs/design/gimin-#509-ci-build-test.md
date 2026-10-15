# PR·develop push에서 빌드와 테스트를 실행하는 CI 추가 (#509)

closes #509

## 1. 배경

- `.github/workflows`에는 `create-issue-branch.yml`(이슈 브랜치 생성)과 `monitoring-validation.yml`(모니터링 설정
  경로 한정)만 있다. PR이나 `develop` push에서 백엔드·프런트엔드 빌드와 테스트를 실행하는 워크플로가 없다.
- 기본 테스트는 로컬에서만 실행했고 결과는 `docs/test-results/`에 손으로 기록했다. 2차 평가(10/12~10/28)는 저장소
  상태를 기준으로 진행되므로, 평가자가 저장소에서 바로 확인할 수 있는 자동 실행 기록이 필요하다.

## 2. 결정

`.github/workflows/ci.yml`을 추가한다. Spring 설정은 바꾸지 않는다.
통합 테스트 준비 단계의 `TRUNCATE` deadlock 재시도는 이 CI PR에 포함한다(3.2.2).
CI에서 발견한 Attempt 시각 정밀도 수정은 별도 [Fix PR #512](https://github.com/DocGrid/docgrid/pull/512)로 분리했다.

PR #510은 #512를 선행 변경으로 사용하며, 분리 검토 중에는 base를 `fix/511`로 둔다.
#512 병합 후 base를 `develop`으로 되돌리고 최종 커밋에서 3회 연속 전체 job 합격 기준을 다시 확인한다.

| 항목 | 내용 |
|---|---|
| 트리거 | `pull_request`, `develop` push, `workflow_dispatch` |
| 동시 실행 | 같은 PR 또는 ref의 이전 실행을 취소(`concurrency`, `cancel-in-progress: true`) |
| 권한 | `contents: read` |
| `backend` job | Java 17(Temurin), Gradle 캐시, pgvector·Valkey 서비스 컨테이너, `vector` 확장 생성 뒤 `./backend/gradlew -p backend build --no-daemon`. 성공·실패와 관계없이 JUnit XML·HTML 리포트 업로드. `timeout-minutes: 40` |
| `frontend` job | Node 22, npm 캐시, `npm --prefix frontend ci` → `run lint` → `test`(production 빌드 포함). `timeout-minutes: 15` |

### 2.1 실행 범위

`build`는 `compileJava`·`test`·`bootJar`(루트 `LICENSE` 포함)를 함께 실행한다. `test` 태스크의 범위는 로컬 README
「테스트」 절의 `./backend/gradlew -p backend test`와 같다.

| 구분 | 내용 |
|---|---|
| 포함 | 단위 테스트, `integration` 태그 52개 클래스, 태그 없이 `test` 프로필 PostgreSQL을 쓰는 Spring 테스트 16개 클래스, `opensql-verification` 태그(제외 목록에 없음) |
| 제외 | `build.gradle`의 `excludeTags` 18개(benchmark, 실제 인프라 E2E, OpenSQL HA·Outbox 격리 시험 등). 별도 Gradle 태스크로만 실행한다 |

### 2.2 서비스 컨테이너

| 서비스 | 설정 | 이유 |
|---|---|---|
| PostgreSQL | `pgvector/pgvector:0.8.1-pg17@sha256:3e8b3adf…5d700b21`, `55432:5432`, DB·사용자 `app`, 비밀번호 `local_password` | `application-test.yml` 기본 접속값과 같아 `DB_*` 변수를 주지 않아도 된다. `OpenSqlCompatibilityIntegrationTest`가 `server_version` 접두사 `17.8`과 `vector` 확장 버전 `0.8.1`을 단언하므로 태그 대신 digest로 고정한다(2026-02-13 빌드, `PG_VERSION=17.8-1.pgdg12+1`, Docker Hub에서 index digest 확인) |
| Valkey | `valkey/valkey:9.1-alpine`, `6379:6379` | `AuthLogoutIntegrationTest`, `StompDashboardRealRoleRevocationIntegrationTest`가 실제 Redis 프로토콜 서버를 쓴다. `docker-compose.yml`과 같은 태그 |

- `vector` 확장은 Flyway가 아니라 로컬 `docker/postgres/init/001-enable-vector.sql`이 만든다. 서비스 컨테이너에는
  저장소 파일을 마운트할 수 없으므로 테스트 전에 `docker exec … psql`로
  `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public`을 실행한다. 같은 단계에서 `SHOW server_version`과
  `vector` 확장 버전을 출력해 실행 로그에 남긴다. `pg_trgm`은 V46 마이그레이션이 만든다.
- `ManagementEndpointIntegrationTest`는 H2 메모리 DB를 쓰므로 서비스와 무관하다.

### 2.3 환경 변수

| 변수 | 값 | 이유 |
|---|---|---|
| `JWT_SECRET` | CI 전용 고정 문자열 | `jwt.secret: ${JWT_SECRET}`에 기본값이 없어 Context가 뜨지 않는다. 운영·로컬 시크릿과 무관한 시험 값이다 |
| `REDIS_HOST`, `REDIS_PORT` | `localhost`, `6379` | Valkey 서비스 접속 |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | `3` | 여러 Spring Context의 연결 풀 합계가 PostgreSQL `max_connections`(100)를 넘으면 `too many clients`가 난다. #456 기록에서 풀 크기 3일 때 최대 연결이 45/100이었고, #505 전체 회귀(`backend-full-03`)도 기본 풀 크기에서 같은 원인으로 실패했다 |

### 2.4 예상 skip

다음 테스트는 전용 환경 변수가 있을 때만 실행되도록 `assumeTrue`로 막혀 있다. CI에서는 해당 변수를 주지 않으므로
**건너뜀**으로 집계되는 것이 정상이다. 합계 5건이며, #505 로컬 전체 회귀(`backend-full-04`)의 건너뜀 5건과 같다.

| 테스트 | 건수 | 실행 조건 |
|---|---|---|
| `DashboardCrossNodeSignalRedisIntegrationTest` | 1 | `DOCGRID_TEST_REDIS_PORT` |
| `DashboardCrossNodeWebSocketIntegrationTest` | 1 | `DOCGRID_TEST_REDIS_PORT`와 `DB_PORT` |
| `HaProbeIdempotentPostgresIntegrationTest` | 3 | `HA_PROBE_TEST_JDBC_URL`이 `jdbc:postgresql://127.0.0.1:`로 시작 |

### 2.5 대안과 선택 이유

| 대안 | 판단 |
|---|---|
| 통합 테스트를 태그로 나눠 DB 없는 job과 DB job으로 분리 | 태그 없는 Spring 테스트 16개 클래스도 `test` 프로필 PostgreSQL을 쓴다. 나누려면 테스트 코드에 태그를 추가해야 하고, 그래도 Gradle 컴파일·Context 준비가 두 번 돈다. 한 job에 서비스를 붙이면 로컬 README 명령과 범위가 같다 |
| `gradle/actions/setup-gradle`로 Gradle 캐시 | v6부터 캐시 기능이 비공개 상용 컴포넌트(`gradle-actions-caching`)로 분리되어 별도 이용약관 동의가 필요하다. 라이선스 검증 대상 저장소에는 MIT 라이선스인 `actions/setup-java`의 `cache: gradle`을 쓴다 |
| pgvector를 태그(`0.8.1-pg17`)로 지정 | 태그가 다시 빌드되어 PostgreSQL 마이너 버전이 바뀌면 `OpenSqlCompatibilityIntegrationTest`의 `17.8` 단언이 깨진다. 단언을 느슨하게 하지 않고 이미지를 고정한다 |
| `--build-cache` 사용 | 테스트 결과를 캐시에서 재사용하면 CI가 실제로 테스트를 실행했다는 근거가 약해진다. 켜지 않는다 |
| Gradle Daemon 사용 | 일회성 Runner에서는 이득이 없고 종료 뒤 프로세스가 남는다. `--no-daemon`으로 실행한다 |
| PR에 README 배지 포함 | 병합 전에는 `develop` push 실행 기록이 없어 배지가 "no status"로 보인다. 병합 뒤 `develop` push 실행 성공을 확인하고 `branch=develop&event=push` 배지를 별도 커밋으로 추가한다 |

## 3. 검증

draft PR의 같은 커밋에서 첫 실행 뒤 "Re-run all jobs"를 두 번 실행해 전체 job을 3회 연속 통과시키는 것을 합격
기준으로 한다. 실패한 job만 재실행한 것은 횟수에 넣지 않는다. 실패가 나오면 원인을 먼저 보고하고, 수정 커밋이
들어가면 3회를 처음부터 다시 센다. 건수는 업로드된 JUnit XML 기준이다.

### 3.1 수정 전 실행 (합격 횟수에 넣지 않음)

| 순서 | 커밋 | backend 결과 | 실행·실패·건너뜀 | backend 소요 | frontend 결과 | frontend 소요 | 실행 링크 |
|---|---|---|---|---|---|---|---|
| 1 | `93c1877` | 실패 (3.2.1) | 1,489·1·5 | job 5분 11초 (Gradle `BUILD FAILED in 4m 44s`) | 통과, 49·0·0 | job 43초 | [38773938963](https://github.com/DocGrid/docgrid/actions/runs/38773938963) |
| 2 | `175bb74` | 통과 | 1,489·0·5 | job 5분 1초 (Gradle `BUILD SUCCESSFUL in 4m 31s`) | 통과, 49·0·0 | job 29초 | [38775563377 #1](https://github.com/DocGrid/docgrid/actions/runs/38775563377/attempts/1) |
| 3 | `175bb74` | 실패 (3.2.2) | 1,489·1·5 | job 4분 3초 (Gradle `BUILD FAILED in 3m 33s`) | 통과, 49·0·0 | job 31초 | [38775563377 #2](https://github.com/DocGrid/docgrid/actions/runs/38775563377/attempts/2) |

- `SHOW server_version`: `17.8 (Debian 17.8-1.pgdg12+1)`, `vector` 확장 버전 `0.8.1`. digest 고정이 의도대로 동작했다.
- frontend: Node `v22.23.3`, 패키지 502개 설치, lint 통과, production 빌드 성공.
- 건너뜀 5건은 2.4의 예상 skip과 같다.

### 3.2 수정 전 실행의 실패 원인과 수정

#### 3.2.1 Attempt 시작 시각 정밀도 (확정 실패, 별도 Fix PR로 분리)

- 실패: `EmbeddingJobAttemptIntegrationTest`의 "현재 Claim의 순차 재전송은 기존 Attempt를 반환하고 새 Claim은
  다음 번호를 만든다". 같은 요청을 두 번 보낸 응답이 같아야 하는데 `startedAt`이 달랐다.
  - 최초 응답: `2026-10-15T17:06:20.436367321`
  - 재전송 응답: `2026-10-15T17:06:20.436367`
- 원인: 최초 응답은 메모리의 시각을 사용하고 재전송 응답은 DB에서 읽은 시각을 사용한다.
  PostgreSQL `timestamp`는 마이크로초까지만 저장하므로 더 정밀한 Clock 값이 들어오면 두 응답이 달라질 수 있다.
- 범위: Linux CI의 기존 통합 테스트에서 관측한 불일치다.
  운영 환경에서의 발생 여부와 OS별 Clock 정밀도 차이는 이번 분리 작업에서 검증하지 않았다.
- 수정: `LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)` 적용은 별도
  [Fix PR #512](https://github.com/DocGrid/docgrid/pull/512)에서 검토한다.
  이 CI PR의 변경 비교에는 운영 코드가 포함되지 않는다.
- 후속 검증: 고정된 고정밀 Clock과 실제 PostgreSQL 왕복을 사용하는 수정 전 실패·수정 후 통과 시험은
  #511의 후속 작업이며 이번 분리에서는 작성·실행하지 않았다.

#### 3.2.2 테스트 준비 단계의 `TRUNCATE` deadlock (간헐 실패)

- 실패: `DocumentIndexingFailureIntegrationTest`의 "RETRY 이벤트 저장 실패 시 Attempt와 Job의 모든 실패 변경을
  Rollback한다". 테스트 본문이 아니라 `@BeforeEach resetState()`의 `TRUNCATE TABLE … RESTART IDENTITY CASCADE`가
  PostgreSQL `deadlock detected`(SQLState `40P01`)로 중단됐다. 같은 커밋의 직전 실행(순서 2)은 통과했다.
- 원인: PostgreSQL 서버 로그에서 상대 세션의 쿼리를 확인했다.
  - 앞 테스트가 인덱싱 Job 상태를 바꾸면, 같은 Spring Context의 `DashboardPushScheduler`가 300ms 주기 안에
    `DashboardQueryService.getSummary()`를 백그라운드에서 실행한다.
  - 이 집계는 읽기 트랜잭션 하나에서 `documents`를 먼저 읽고 이어서 `embedding_jobs`의 평균 처리 시간을 읽는다.
  - 다음 테스트의 `TRUNCATE`가 `embedding_jobs`의 `AccessExclusiveLock`을 먼저 잡은 상태에서, 집계 트랜잭션이
    이미 읽어 잠금을 쥐고 있던 관계를 기다렸다. 집계가 `embedding_jobs` 전에 읽은 것은 `documents` 집계뿐이다.
    그 사이 집계 트랜잭션은 `embedding_jobs`를 기다려 교착이 났다.
- 판단: 운영 코드에는 `TRUNCATE`가 없으므로 제품 결함이 아니라 테스트 격리 문제다. 타이밍이 맞을 때만 재현된다.
- 수정: 테스트 공통 헬퍼 `TruncateDeadlockRetry`를 추가했다. SQLState `40P01`인 경우에만 200ms 뒤 최대 3회까지
  다시 실행한다. 다른 오류는 그대로 던진다. `TRUNCATE`는 다시 실행해도 결과가 같다.
  - 적용 범위: 기본 `test` 태스크에 포함되고, 준비 단계에서 `TRUNCATE`를 자동 커밋으로 실행하는
    `@SpringBootTest` 통합 테스트 11개. 이들 Context에는 대시보드 push 같은 백그라운드 스케줄러가 있다.
  - 적용하지 않은 곳: 백그라운드 스케줄러가 없는 `@DataJpaTest` 5개, `excludeTags`로 CI에서 빠지는
    benchmark·`claim-concurrency` 테스트 7개.
  - 테스트 단언은 바꾸지 않았다. 헬퍼 동작은 `TruncateDeadlockRetryTest` 3건으로 검증한다.
- 대안: 해당 Context에서 대시보드 스케줄러를 Mock으로 바꾸면 다른 백그라운드 조회와의 충돌이 남는다. Gradle
  테스트 재시도 플러그인은 3.2.1 같은 확정 실패까지 가릴 수 있어 쓰지 않는다.

### 3.3 합격 판정 실행 (수정 커밋 기준 3회)

분리 전 커밋의 통과 횟수는 새 커밋에 승계하지 않는다. #512 병합과 base 복원 후 최종 커밋 기준으로 다시 기록한다.

| 회차 | 커밋 | backend 결과 | 실행·실패·건너뜀 | backend 소요 | frontend 결과 | frontend 소요 | 실행 링크 |
|---|---|---|---|---|---|---|---|
| 1 | | | | | | | |
| 2 | | | | | | | |
| 3 | | | | | | | |

- `timeout-minutes`는 측정한 소요 시간을 보고 조정한다.

## 4. 범위 밖

- Attempt 시작 시각 정밀도 수정과 새 회귀 테스트: 별도 #511 / #512.

- `excludeTags` 18개 태그 시험의 CI 실행. 실제 MinIO·BGE-M3·OpenSQL 3노드 등 외부 자원이 필요하다.
- 프런트엔드 Node 버전 행렬. `engines.node >=22.13.0`의 하한 메이저인 22만 실행한다.
- README CI 배지. 병합 뒤 별도 커밋으로 추가한다.
