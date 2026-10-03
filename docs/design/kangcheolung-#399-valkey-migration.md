# #399 캐시 서버를 Redis 7.4에서 Valkey로 교체

closes #399

---

## 배경

대회의 오픈소스 라이선스 평가 기준(OSI 승인 라이선스)에 비추어 보면, 로컬·테스트용 compose가 실행하는 캐시 서버가 문제였다.

- `docker-compose.yml`은 `redis:7-alpine`을 썼다. 이 태그는 7 메이저의 **최신**을 따라가므로 실제로는 **Redis 7.4.10**이 실행됐다. (직접 확인: `redis-server --version` → `v=7.4.10`)
- Redis 7.4.x의 라이선스는 RSALv2 / SSPLv1이며 OSI 승인을 받지 못했다. (팀에서 공유받은 정리 자료 기준)
- 평가는 각 평가 시점의 저장소 상태로 이루어지고 2차 평가는 10/12~10/28이므로, 그 전에 반영해야 한다.

| 선택지 | 라이선스 | OSI 승인 |
|---|---|---|
| Redis 7.2.x 이하 | BSD-3-Clause | 승인 |
| **Redis 7.4.x (기존 compose)** | RSALv2 / SSPLv1 | **미승인** |
| Redis 8.x | RSALv2 / SSPLv1 / AGPLv3 중 택1 | AGPLv3 선택 시 승인 |
| **Valkey (채택)** | BSD-3-Clause | 승인 |

> 문제가 된 것은 "Redis"라는 이름이 아니라 **Redis 7.4 서버 프로그램의 라이선스**다. 서버와 통신하는 클라이언트 라이브러리는 별개의 소프트웨어이며 아래와 같이 라이선스 문제가 없다.

| 구성요소 | 역할 | 라이선스 | 확인 방법 |
|---|---|---|---|
| Valkey | 캐시 서버 | BSD-3-Clause | 공식 저장소(valkey-io/valkey)에 명시, LF Projects 거버넌스 |
| Spring Data Redis | 자바 클라이언트(`StringRedisTemplate` 등) | Apache 2.0 | 공식 저장소(spring-projects/spring-data-redis)에 명시 |
| Lettuce 6.6.0 | 실제 접속 엔진 | MIT | 이 프로젝트가 내려받은 POM의 `<licenses>` |

---

## 결정

**서버 프로그램만 Valkey로 교체한다. 클라이언트 라이브러리·설정·환경변수·자바 코드는 바꾸지 않는다.**

Valkey는 Redis 7.2.4에서 갈라져 나온 포크로 같은 Redis 프로토콜(RESP)을 구현한다. 실제로 Valkey에 `INFO server`를 요청하면 호환용으로 `redis_version:7.2.4`를 함께 보고한다(`valkey_version:9.1.2`, `server_name:valkey`).

```text
앱(자바)
  └─ Spring Data Redis / Lettuce   ← 유지 (Redis 프로토콜로 말하는 클라이언트)
       └─ spring.data.redis.*, REDIS_* 환경변수   ← 유지
            └─ 서버: Redis 7.4  →  Valkey 9.1   ← 이번에 교체
```

### 이름을 유지하는 것

| 이름 | 위치 | 유지 이유 |
|---|---|---|
| `StringRedisTemplate`, `RedisScript`, `RedisMessageListenerContainer` | 자바 코드 3곳 | Spring Data Redis 라이브러리의 클래스. 우리가 바꿀 수 없다 |
| `spring.data.redis.*` | `application.yml` | Spring Boot가 이 이름으로 접속 설정을 읽는다. 지우면 접속 정보를 읽지 못한다 |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` 등 | `application.yml`, compose, 스크립트 | GCP A/B 환경 등 레포 밖 배포 설정이 이 이름에 의존한다. 일관성을 위해 바꾸려면 인프라 담당과 같이 바꿔야 해서 이번 범위에서 제외 |

### 선택하지 않은 방법

- **Redis 7.2로 고정**: BSD라 통과하지만 구버전에 묶이는 것이어서 Valkey보다 나은 점이 없다.
- **Redis 8 + AGPLv3**: 기준은 통과하지만 Apache-2.0 프로젝트에 AGPL 구성요소를 섞는 설명 부담이 생긴다.
- **Spring Data Valkey(valkey-io, Apache 2.0)로 클라이언트까지 교체**: `spring-boot-starter-data-valkey` 의존성과 `StringValkeyTemplate` 등 클래스명 변경으로 코드 3곳을 고쳐야 한다. 라이선스 문제는 서버에만 있어 얻는 이득이 이름 통일뿐이고, 이 프로젝트의 Spring Boot 3.5와의 호환은 확인하지 못했다. 필요하면 별도 이슈로 검토한다.

---

## 변경 범위

### 변경한 파일

| 파일 | 변경 |
|---|---|
| `docker-compose.yml` | 서비스 `redis` → `valkey`, 이미지 `redis:7-alpine` → `valkey/valkey:9.1-alpine`(마이너까지 고정), 컨테이너 `docgrid-valkey`, 볼륨 `valkey-data`, healthcheck `valkey-cli ping` |
| `monitoring/drills/docker-compose.yml` | 서비스·이미지·healthcheck 동일 변경 |
| `monitoring/drills/run_drill.py` | 서비스명 참조 2곳(`start_core`의 서비스 목록, `service_port("valkey", 6379)`) |
| `scripts/local-services.sh` | `valkey_ready`(`docker compose exec -T valkey valkey-cli ping`), `up -d valkey`, 안내 문구 |
| `README.md` | 기술 스택 표(`Valkey 9.1 (Redis 프로토콜 호환, 클라이언트: Spring Data Redis)`), compose 명령어와 안내 문구 7곳 |
| `.env.example` | Valkey 사용 및 변수명 `REDIS_*` 유지 이유 설명 |

호스트 포트 매핑 변수는 `${REDIS_PORT:-6379}` 그대로이므로 기존 환경 설정이 유지된다.

### 변경하지 않은 것

- 자바 코드, `application.yml`, `build.gradle` 의존성(`spring-boot-starter-data-redis`)
- `docs/test-results/**`: 과거에 실제 Redis로 시험한 기록이라 소급 수정하지 않는다. 수정하면 증거가 왜곡된다.
- `docs/design/**`의 기존 설계 문서: "Redis 캐시", "Redis blacklist"는 프로토콜/기능 이름으로 여전히 유효하다.
- `scripts/opensql/*.py`: Redis 프로토콜을 소켓으로 직접 말하는 구현이라 서버 종류와 무관하다.

---

## 호환성 근거

이 프로젝트가 Redis 서버에 쓰는 기능은 아래뿐이며 모두 Valkey가 지원한다.

| 기능 | 사용처 |
|---|---|
| `SET` + TTL, `GET`, `MGET`, `DEL` | `TokenBlacklistService`(로그아웃 토큰 블랙리스트), `RoleAuthorityService`(역할 캐시) |
| Lua `EVAL`/`EVALSHA` | `RoleAuthorityService`의 캐시 무효화·조건부 캐시 스크립트 |
| Pub/Sub (`PUBLISH`/`SUBSCRIBE`) | `DashboardCrossNodeSignal`(두 백엔드 사이 대시보드 갱신 신호) |

---

## 검증

### 환경 (개발 DB와 완전히 분리)

| 항목 | 값 |
|---|---|
| 기준선 서버 | `redis:7-alpine` = Redis 7.4.10 (임시 컨테이너, 호스트 16379) |
| 전환 후 서버 | **변경한 `docker-compose.yml`의 `valkey` 서비스** = Valkey 9.1.2, 이미지 `valkey/valkey:9.1-alpine`(digest `sha256:48332870af35...`), 호스트 16379 |
| DB | 임시 `pgvector/pgvector:0.8.1-pg17` (호스트 55432, DB `app`), 테스트 스키마 `docgrid_test` |
| 접속 정보 | `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`, `REDIS_HOST/REDIS_PORT`, `DOCGRID_TEST_REDIS_PORT`를 환경변수로 덮어씀 |

기준선과 전환 후는 서버만 다르고 DB·테스트 설정·명령이 같다.

### 결과: Redis 관련 테스트 7개 클래스

| 테스트 | 종류 | 테스트 수 | Redis 7.4.10 | Valkey 9.1.2 |
|---|---|---|---|---|
| `StompDashboardRealRoleRevocationIntegrationTest` | 실제 서버(Lua `EVAL`, 캐시 무효화) | 4 | 통과 | 통과 |
| `DashboardCrossNodeSignalRedisIntegrationTest` | 실제 서버(Pub/Sub, 재구독) | 1 | 통과 | 통과 |
| `DashboardCrossNodeWebSocketIntegrationTest` | 실제 서버(앱 2대 + Pub/Sub + STOMP) | 1 | 통과 | 통과 |
| `ManagementEndpointIntegrationTest` | 서버 없이(Redis 장애 시 fail-open) | 4 | 통과 | 통과 |
| `RoleAuthorityServiceTest` | 단위(mock) | 6 | 통과 | 통과 |
| `TokenBlacklistServiceTest` | 단위(mock) | 5 | 통과 | 통과 |
| `DashboardCrossNodeSignalTest` | 단위(mock) | 4 | 통과 | 통과 |
| **합계** | | **25** | **실패 0, 건너뜀 0** | **실패 0, 건너뜀 0** |

### 결과: 전체 테스트

| | Redis 7.4.10 | Valkey 9.1.2 |
|---|---|---|
| `./backend/gradlew -p backend test` | 1297개 통과 | 1297개 통과 |
| 실패 / 에러 / 건너뜀 | 0 / 0 / 0 | 0 / 0 / 0 |
| 클래스별 결과 | — | 기준선과 **완전히 동일** |

전체 220개 테스트 클래스의 클래스별 결과는 기준선·전환 후가 파일 단위로 **차이 없음**(`diff` 결과 빈 출력)이다. 원본은 아래 증거 파일에 있다.

| 증거 파일 | 내용 |
|---|---|
| [full-redis-7.4.10-summary.txt](../test-results/evidence/issue-399/full-redis-7.4.10-summary.txt) | 기준선(Redis 7.4.10) 전체 220개 클래스별 tests/fail/err/skipped |
| [full-valkey-9.1.2-summary.txt](../test-results/evidence/issue-399/full-valkey-9.1.2-summary.txt) | 전환 후(Valkey 9.1.2) 동일 집계 |
| [verify-e2e.log](../test-results/evidence/issue-399/verify-e2e.log) | `./monitoring/verify.sh --e2e` 출력 |
| [summarize.py](../test-results/evidence/issue-399/summarize.py) | Gradle XML 리포트(`build/test-results/test`)를 클래스별로 집계하는 스크립트 |

### Redis 관련 테스트 케이스 상세 (25개, 기준선·전환 후 모두 통과)

소요 시간은 `Redis 7.4.10 / Valkey 9.1.2` 순서이며 전체 실행 중 측정한 값이다.

**`StompDashboardRealRoleRevocationIntegrationTest`** — 실제 서버(역할 캐시 Lua 무효화, 실제 HTTP·DB·STOMP)

| 케이스 | 시간 |
|---|---|
| 권한 확인을 마친 진행 중 메시지는 회수 응답 뒤에도 도착할 수 있다 | 0.98s / 0.97s |
| HTTP 역할 회수 뒤 주기 재검증 없이 새 구독과 기존 구독 push가 차단된다 | 0.75s / 0.75s |
| 실제 발행 코드의 primary 판정은 outbound에 남고 STOMP 클라이언트에는 노출되지 않는다 | 0.18s / 0.17s |
| Redis 무효화 실패로 낡은 ADMIN 캐시가 남아도 새 대시보드 push를 차단한다 | 0.60s / 0.59s |

**`DashboardCrossNodeSignalRedisIntegrationTest`** — 실제 서버(Pub/Sub)

| 케이스 | 시간 |
|---|---|
| A/B 신호가 반대편에만 도착하고 재구독 시 놓친 상태를 다시 읽는다 | 0.33s / 0.39s |

**`DashboardCrossNodeWebSocketIntegrationTest`** — 실제 서버(독립 Spring 앱 2대 + Pub/Sub + STOMP 구독)

| 케이스 | 시간 |
|---|---|
| A/B 어느 쪽만 발행해도 반대편은 자기 DB 요약을 다시 계산해 자기 관리자에게 보낸다 | 4.79s / 5.23s |

**`ManagementEndpointIntegrationTest`** — 서버 없이(연결 불가 포트로 Redis 장애 재현)

| 케이스 | 시간 |
|---|---|
| health probe와 Prometheus는 Management 포트에서 인증 없이 조회한다 | 0.04s / 0.04s |
| Prometheus 출력에 JVM, HTTP 서버, HikariCP 메트릭이 포함된다 | 0.02s / 0.02s |
| Redis를 사용할 수 없어도 DB가 정상이면 readiness는 UP이다 | 1.05s / 1.05s |
| 민감한 Actuator 엔드포인트와 애플리케이션 포트의 Actuator 경로는 노출하지 않는다 | 0.02s / 0.01s |

**단위 테스트(mock)** — Redis 서버에 접속하지 않으므로 호환성 검증이 아닌 회귀 확인용이다.

| 클래스 | 케이스 |
|---|---|
| `RoleAuthorityServiceTest` (6) | Redis 조회 실패 시 DB 폴백 / DB 조회 중 권한 변경 시 오래된 역할 미반환·미캐시 / 관리자 요청은 캐시를 읽지 않고 primary 조회 / 무효화 후 DB 재조회 / 캐시 히트 시 DB 미조회 / 캐시 미스 시 DB 조회 후 캐싱 |
| `TokenBlacklistServiceTest` (5) | MGET 응답이 불완전하면 실패 / 등록된 jti는 true / 여러 jti를 MGET 한 번으로 조회 / 미등록 jti는 false / 남은 만료 시간만큼 TTL로 저장 |
| `DashboardCrossNodeSignalTest` (4) | 재구독 시 놓친 변경 재조회 표시 / 비활성·발행 오류가 로컬 push를 실패시키지 않음 / 고정 채널에 인스턴스 ID만 발행하고 자기 echo 무시 / 다른 백엔드 신호는 원격 dirty만 세우고 재발행하지 않음 |

### 실행 이력

시행착오를 포함해 실제로 실행한 순서다. 1~2번은 환경 격리가 불완전했던 시험 실행이고, 3번부터가 비교에 쓴 측정이다.

| # | 서버 | DB | 범위 | 결과 | 소요 |
|---|---|---|---|---|---|
| 1 | Redis 7.4.10 | `backend/.env`의 개발 DB(`docgrid_test` 스키마) | 7개 클래스 | 25개 중 24 통과, **1 건너뜀** (`DashboardCrossNodeWebSocketIntegrationTest`: `DB_PORT` 미지정으로 `assumeTrue` 건너뜀) | 32s |
| 2 | Redis 7.4.10 | 임시 PostgreSQL을 가리키도록 `DB_PORT`만 지정 | 7개 클래스 | **5 실패** (STOMP 4 + A/B 앱 1). 원인: 환경변수로 덮어쓰지 않은 `DB_USER` 등이 `backend/.env`의 개발용 값으로 적용되어 임시 DB에서 인증 실패 → Flyway/컨텍스트 로딩 실패. Redis와 무관 | 14s |
| 3 | Redis 7.4.10 | 임시 PostgreSQL, 접속 정보 전부 덮어씀 | 7개 클래스 | **25 통과**, 건너뜀 0 | 22s |
| 4 | Redis 7.4.10 | 임시 PostgreSQL | 전체 | **1297 통과**, 건너뜀 0 | 1m 40s |
| 5 | **Valkey 9.1.2** | 임시 PostgreSQL | 7개 클래스 | **25 통과**, 건너뜀 0 | 23s |
| 6 | **Valkey 9.1.2** | 임시 PostgreSQL | 전체 | **1297 통과**, 건너뜀 0 | 1m 37s |
| 7 | — | — | `./monitoring/verify.sh --e2e` | 통과 | — |

### Valkey가 실제로 호출됐는지 (서버 명령 통계, 컨테이너 시작 후 누적)

| 명령 | 호출 수 | 비고 |
|---|---|---|
| `EVAL` | 2 | 스크립트 최초 로드 |
| `EVALSHA` | 18 | 이 중 `failed_calls=2` |
| `SUBSCRIBE` / `PUBLISH` | 10 / 10 | 대시보드 Pub/Sub |
| `GET` / `MGET` / `SET` / `DEL` | 70 / 17 / 23 / 36 | 블랙리스트·역할 캐시 |
| 전체 오류 응답 | 2 | `EVALSHA` 실패 2건뿐 |

`EVALSHA`의 실패 2건은 `EVAL` 호출 2건과 짝이 맞는다. Spring이 스크립트를 처음 실행할 때 `EVALSHA`를 먼저 시도하고, 서버에 스크립트가 없어 오류(`NOSCRIPT`)가 나면 `EVAL`로 다시 보내는 정상 동작으로 해석된다. 그 외 오류 응답은 없다.

### 그 외 확인

| 항목 | 결과 |
|---|---|
| `docker compose up -d --wait valkey` | healthy (healthcheck `valkey-cli ping`) |
| `./scripts/local-services.sh status` | `Valkey 연결 가능` (Embedding Provider 실패는 아래 "발견한 사항" 참고, 이번 변경과 무관) |
| drill compose의 `valkey` 단독 기동 | healthy, `docker compose port valkey 6379`로 동적 포트 조회 성공 (`run_drill.py`가 쓰는 방식) |
| 이미지 확인 | Docker Hub에 `valkey/valkey:9.1-alpine` 태그 존재(마지막 갱신 2026-09-21), `valkey-server --version` = 9.1.2, 이미지에 `valkey-cli`와 호환용 `redis-cli`가 모두 포함(healthcheck·스크립트는 `valkey-cli` 사용) |
| `INFO server` 응답 | `redis_version:7.2.4`(호환용), `valkey_version:9.1.2`, `server_name:valkey` |
| 로그아웃 차단 키 등록·조회 (실제 PostgreSQL·Valkey) | `AuthLogoutIntegrationTest`(`로그아웃 PostgreSQL·Redis 통합 테스트`) 통과. 로그인한 토큰은 로그아웃 전 `isBlacklisted=false`, `AuthCommandService.logout()` 뒤 `true`로 Valkey에 `auth:blacklist:<jti>`가 저장·조회된다. 전체 테스트 결과표에 포함되어 있다. 서비스 계층에서 확인한 것이며, 앱을 직접 띄워 HTTP로 호출하는 수동 확인은 하지 않았다 |
| `docker compose config -q` (루트·drill) | 통과 |
| `bash -n scripts/local-services.sh`, `py_compile run_drill.py` | 통과 |
| `./monitoring/verify.sh --e2e` (CI와 동일) | 통과 (`Monitoring configuration validation: SUCCESS`, Alertmanager routing E2E 성공) |

---

## 검증 중 발견한 사항

1. **CI는 Redis 컨테이너를 실제로 띄우지 않는다.** `monitoring-validation.yml`이 실행하는 `verify.sh --e2e`는 drill compose를 `config --quiet`로 렌더링만 한다. 그래서 이 변경이 CI에서 확인되는 것은 "compose가 올바르게 렌더링되는가"까지다. 실제 Valkey 동작은 위 로컬 검증으로 확인했다.
2. **로컬 6379 포트의 Redis는 Docker가 아니라 Homebrew `redis-server` 8.4.0이다.** 호스트 프로세스(5월 22일부터)이고, compose의 `docgrid-redis` 컨테이너는 한 번도 시작되지 않은 `Created` 상태였다. 즉 개발자 로컬 앱은 compose가 아닌 Homebrew Redis에 붙고 있었다. Redis 8.x는 RSALv2/SSPLv1/AGPLv3 중 택1이다. 저장소 산출물이 아니라 개인 PC 환경이지만, 로컬 앱을 Valkey로 돌리려면 `brew services stop redis`로 멈추거나 `REDIS_PORT`를 바꿔야 6379 충돌이 없다.
3. **`application.yml`이 `backend/.env`를 불러온다(`spring.config.import: optional:file:.env[.properties]`).** 테스트 실행 시 환경변수로 덮어쓰지 않은 값은 개발용 DB 설정으로 접속한다. 이번 검증의 첫 시험 실행이 그랬고, 개발 DB의 `docgrid_test` 스키마에 아직 적용되지 않았던 Flyway 마이그레이션 1건이 적용됐다(`public` 스키마의 개발 데이터는 건드리지 않았고 테스트 잔여 데이터도 없음). 이후 모든 측정은 DB·Redis 접속 정보를 전부 환경변수로 덮어써 임시 컨테이너만 사용했다.
4. **로컬 `docgrid-embedding` 컨테이너 이미지가 오래됐다(2026-08-15 빌드).** `local-services.sh`가 호출하는 `/health/ready`가 없어(404) `status`가 Embedding Provider를 "준비 안 됨"으로 표시한다. 소스(`backend/embedding-server/main.py`)에는 해당 경로가 있으므로 컨테이너 재빌드로 해결되며 이번 변경과 무관하다.
5. **Pub/Sub 통합 테스트는 환경변수 없이는 건너뛴다.** `DashboardCrossNodeSignalRedisIntegrationTest`는 `DOCGRID_TEST_REDIS_PORT`, `DashboardCrossNodeWebSocketIntegrationTest`는 거기에 `DB_PORT`까지 있어야 실행된다. 없으면 `assumeTrue`로 조용히 건너뛰어 통과처럼 보이므로, 검증 시 "건너뜀 0"을 확인해야 한다. (처음 실행에서 이 테스트가 건너뛰어졌고 환경변수를 추가해 해결했다.)
6. **`minio/minio` 이미지는 태그가 없는 `latest`다.** 이번 Redis 건과 같은 유형(떠다니는 태그)이다. 로컬에는 13개월 된 이미지가 캐시되어 있어 동작하지만, 새 환경에서 `docker pull minio/minio`가 되는지는 확인하지 못했다(Docker Hub API 조회는 "not found"를 반환했으나 이것만으로 단정할 수 없음). 별도로 확인이 필요하다.

---

## 이 PR 밖에서 확인할 것

| 항목 | 내용 | 담당 |
|---|---|---|
| GCP A/B 공용 Redis VM | Rocky Linux 9.8 VM에 systemd 서비스 `redis`로 설치되어 있다(`docs/test-results/evidence/issue-380`, `issue-382` 참고). 설치 출처와 버전은 문서에 없다. `redis-server --version`으로 확인하고, 7.4 이상이면 Valkey로 교체한 뒤 A/B 시험(#382 시나리오)을 다시 확인해야 한다 | 인프라 담당 팀원 |
| 8/27 제출 SBOM | Redis 항목의 표기·버전·라이선스가 실제(7.4.x, RSALv2/SSPLv1)와 다르게 적혀 있다면 정정본을 레포에 올린다 | SBOM 제출 담당 |
| 운영규정 원문 | 평가 기준이 정말 OSI 승인인지, 이름에 Redis가 들어간 구성요소(클라이언트 라이브러리, 환경변수)가 문제가 되지 않는지 확인한다. 불확실하면 운영사무국에 문의한다 | 팀 |
| 서비스명 변경 공지 | `docker compose up -d redis`를 쓰던 팀원은 `valkey`로 바꿔야 한다 | 팀 전체 |
| 공용·운영 성격 서버 교체·롤백 시 로그아웃 차단 키 | 서버나 볼륨을 바꾸면 `auth:blacklist:*` 키가 넘어가지 않는다. 아래 "서버 교체·롤백 시 로그아웃 차단 키" 참고 | 인프라 담당 팀원 |

---

## 서버 교체·롤백 시 로그아웃 차단 키(`auth:blacklist:*`)

서버나 볼륨을 바꾸면 이 키가 새 서버로 넘어가지 않는다는 점을 알고 진행해야 한다.

- 로그아웃하면 `AuthCommandService.logout()`이 토큰의 `jti`를 `auth:blacklist:<jti>` 키로 저장한다. 키의 TTL은 토큰의 **남은 만료 시간**이다.
- 인증 필터는 이 키가 있으면 토큰을 거부하고, **없으면 허용**한다. 그래서 키가 사라지면 로그아웃한 토큰이 만료되기 전까지 HTTP·STOMP 인증을 다시 통과할 수 있다.
- 같은 서버 안의 역할 캐시는 키가 사라져도 DB에서 다시 채워지지만, **차단 키는 어디에서도 복구되지 않는다.**
- 영향 범위는 교체 시점 직전 `JWT_EXPIRATION`(기본 3600초) 안에 로그아웃한 토큰뿐이다. 그 시간이 지나면 토큰 자체가 만료되어 위험이 사라진다.
- 롤백도 대칭이다. Valkey를 쓰는 동안 생긴 차단 키는 Redis로 자동 이전되지 않는다.

| 환경 | 영향 | 대응 |
|---|---|---|
| 로컬 개발 | 미미함. 개발 PC의 로그아웃 토큰은 보안 경계가 아니다 | 별도 조치 불필요 |
| GCP A/B 공용 서버 등 공용·운영 성격 환경 | 교체·롤백 직전 만료 시간 안에 로그아웃한 토큰이 다시 유효해질 수 있다 | 아래 셋 중 하나 |

공용·운영 성격 환경의 대응 (하나를 선택):
1. 마지막 로그아웃으로부터 토큰 만료 시간(기본 1시간)이 지난 뒤에 교체·롤백한다.
2. 차단 키를 남은 TTL과 함께 새 서버로 옮긴다. 이전 중에 생기는 로그아웃은 놓치지 않도록 쓰기를 막은 상태에서 옮기고, 롤백 때는 교체 이후에 생긴 키도 되돌려야 한다.
3. JWT 서명 키(`JWT_SECRET`)를 교체해 기존 토큰을 모두 무효화한다. 모든 사용자가 다시 로그인해야 한다.

---

## 팀원 로컬 전환 방법

기존 `docgrid-redis` 컨테이너가 6379 포트를 잡고 있으면 새 `valkey`가 뜨지 못하므로, **기존 컨테이너를 먼저 제거**한 뒤 시작한다.

```bash
# 1) 기존 Redis 컨테이너 제거 (없으면 무시)
docker rm -f docgrid-redis

# 2) Valkey 시작 (이전 redis 서비스를 대체합니다)
docker compose up -d --wait valkey

# 3) 선택: 더 이상 쓰지 않는 기존 볼륨 정리 (이름은 `docker volume ls | grep redis-data`로 확인)
docker volume rm docgrid_redis-data
```

볼륨을 지우면 그 안의 로그아웃 차단 키도 사라지지만, 로컬 개발 환경에서는 영향이 미미하다(위 표 참고).

6379 포트를 Homebrew `redis-server`가 쓰고 있다면 먼저 `brew services stop redis`로 멈추거나 `REDIS_PORT`를 다른 값으로 지정합니다.

---

## 재현 방법

```bash
# 1) 개발 DB와 분리된 임시 DB (테스트 스키마는 Flyway가 만든다)
docker run -d --name docgrid-399-pg -p 127.0.0.1:55432:5432 \
  -e POSTGRES_DB=app -e POSTGRES_USER=app -e POSTGRES_PASSWORD=local_password \
  -v "$PWD/docker/postgres/init/001-enable-vector.sql:/docker-entrypoint-initdb.d/001-enable-vector.sql:ro" \
  pgvector/pgvector:0.8.1-pg17

# 2) 전환 후 서버 (변경한 compose 사용, 호스트 포트만 변경)
REDIS_PORT=16379 docker compose up -d --wait valkey

# 3) 전체 테스트: DB·Redis 접속 정보를 모두 환경변수로 덮어써 backend/.env의 개발 DB를 쓰지 않게 한다
DB_HOST=127.0.0.1 DB_PORT=55432 DB_NAME=app DB_USER=app DB_PASSWORD=local_password \
REDIS_HOST=127.0.0.1 REDIS_PORT=16379 DOCGRID_TEST_REDIS_PORT=16379 \
  ./backend/gradlew -p backend cleanTest test

# 4) 서버가 실제로 쓰였는지 확인
docker exec docgrid-valkey valkey-cli INFO commandstats
```

기준선은 2)에서 `valkey` 대신 `docker run -d -p 127.0.0.1:16379:6379 redis:7-alpine`을 띄워 같은 3)을 실행했다.

---

## 롤백

이미지 태그와 이름을 되돌리면 된다(`git revert`). 두 볼륨(`valkey-data`, `redis-data`)에는 로그아웃 차단 키와 역할 캐시뿐이다.

- **역할 캐시**: 사라져도 DB에서 다시 채워지므로 영향이 없다.
- **로그아웃 차단 키**: Valkey 볼륨의 데이터는 기존 Redis 볼륨으로 자동 이전되지 않고, 복구 수단도 없다. 롤백하면 Valkey를 쓰는 동안 생긴 차단 키가 Redis에 없어, 롤백 직전 토큰 만료 시간(기본 3600초) 안에 로그아웃한 토큰이 다시 유효해질 수 있다. 로컬 개발 환경에서는 영향이 미미하고, 공용·운영 성격 환경에서는 위 "서버 교체·롤백 시 로그아웃 차단 키"의 대응을 따른다.
