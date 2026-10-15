# PR·develop push에서 빌드와 테스트를 실행하는 CI 추가 (#509)

closes #509

## 1. 배경

- `.github/workflows`에는 `create-issue-branch.yml`(이슈 브랜치 생성)과 `monitoring-validation.yml`(모니터링 설정
  경로 한정)만 있다. PR이나 `develop` push에서 백엔드·프런트엔드 빌드와 테스트를 실행하는 워크플로가 없다.
- 기본 테스트는 로컬에서만 실행했고 결과는 `docs/test-results/`에 손으로 기록했다. 2차 평가(10/12~10/28)는 저장소
  상태를 기준으로 진행되므로, 평가자가 저장소에서 바로 확인할 수 있는 자동 실행 기록이 필요하다.

## 2. 결정

`.github/workflows/ci.yml`을 추가한다. 애플리케이션 코드, 테스트 코드와 Spring 설정은 바꾸지 않는다.

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

| 회차 | 커밋 | backend 결과 | 실행·실패·건너뜀 | backend 소요 | frontend 결과 | frontend 소요 | 실행 링크 |
|---|---|---|---|---|---|---|---|
| 1 | | | | | | | |
| 2 | | | | | | | |
| 3 | | | | | | | |

- 첫 실행 로그의 `SHOW server_version`: (기록 예정)
- `timeout-minutes`는 측정한 소요 시간을 보고 조정한다.

## 4. 범위 밖

- `excludeTags` 18개 태그 시험의 CI 실행. 실제 MinIO·BGE-M3·OpenSQL 3노드 등 외부 자원이 필요하다.
- 프런트엔드 Node 버전 행렬. `engines.node >=22.13.0`의 하한 메이저인 22만 실행한다.
- README CI 배지. 병합 뒤 별도 커밋으로 추가한다.
