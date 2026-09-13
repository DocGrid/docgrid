# 관측성 파이프라인 실제 장애 주입 검증 결과

## 결론

2026-09-13에 커밋 `9f92f04c638bc7281911886a16c06a0bb244a3da`의 Harness로 세 시나리오를
연속 실행했고 모두 PASS했다.

| 검증 항목 | 결과 |
|---|---:|
| `/actuator/prometheus` 호출 | 300회, 동시성 20 |
| 최초 Snapshot aggregate SQL | 3회 |
| 300회 scrape가 추가한 aggregate SQL | **0회** |
| scrape 응답시간 | p50 8.404ms · p95 41.746ms · max 80.882ms |
| Worker 부재 condition → firing | 72.228초 |
| Worker 부재 condition → webhook | 80.435초 |
| Queue 정체 condition → firing | 311.594초 |
| Queue 정체 condition → webhook | 340.428초 |
| Worker 시작 → 실제 Job `INDEXED` | 8.598초 |
| Worker 시작 → 두 resolved webhook 완료 | 59.715초 |
| Provider stop → `up=0` | 15.527초 |
| Provider stop → firing | 89.811초 |
| Provider stop → webhook | 99.358초 |
| Provider restart → readiness | 5.325초 |
| Provider restart → resolved webhook | 54.478초 |
| 종속 warning 상태 | `suppressed` |
| 종속 warning firing 전달 | **0건** |
| Backend 회귀 테스트 | **1,171개 성공, 실패·건너뜀 0개** |
| Alertmanager routing E2E | firing 2초 · resolved 2초 |

## 실행 환경

| 항목 | 값 |
|---|---|
| Host | macOS Darwin 25.6.0, arm64 |
| Java | Eclipse Temurin 17.0.18+8 |
| Docker Engine | 29.4.1 |
| Docker Compose | 5.1.3 |
| PostgreSQL | `pgvector/pgvector:0.8.1-pg17` |
| Prometheus | `prom/prometheus:v3.5.5` |
| Alertmanager | `quay.io/prometheus/alertmanager:v0.33.1` |
| BGE-M3 | 저장소 `backend/embedding-server/Dockerfile` build |
| cluster / environment | `docgrid-drill` / `local-drill` |

모든 시나리오는 새 Compose project, 동적 loopback port와 새 PostgreSQL volume을 사용했다. BGE-M3
모델 cache만 재사용했다. 최종 실행 뒤 `docker ps`와 drill 이름의 volume 목록이 비어 있는 것을
확인했다.

## 실행 명령

```bash
./monitoring/drills/run.sh all \
  --output-dir /private/tmp/docgrid-drill-final
```

실행기는 Backend `bootJar`를 한 번 만든 뒤 다음 순서로 시나리오를 실행했다.

```text
scrape-load
→ Stack 삭제
→ queue-recovery
→ Stack 삭제
→ provider-outage
→ Stack 삭제
```

최종 원본 결과:

- [`scrape-load.json`](evidence/issue-338/scrape-load.json)
- [`queue-recovery.json`](evidence/issue-338/queue-recovery.json)
- [`provider-outage.json`](evidence/issue-338/provider-outage.json)
- [`summary.json`](evidence/issue-338/summary.json)
- [`queue-recovery-webhooks.jsonl`](evidence/issue-338/queue-recovery-webhooks.jsonl)
- [`provider-outage-webhooks.jsonl`](evidence/issue-338/provider-outage-webhooks.jsonl)

## Scrape DB 부하

### 목적

Queue Gauge callback이 DB를 직접 조회하지 않고 마지막 메모리 Snapshot만 읽는다는 것을 실제
Management HTTP 요청과 PostgreSQL 통계로 검증했다.

### 절차

1. `shared_preload_libraries=pg_stat_statements`, `pg_stat_statements.track=all`로 PostgreSQL을 실행했다.
2. Worker·Sync Dispatcher를 끄고 Snapshot 주기를 10분으로 설정한 실제 Backend를 시작했다.
3. `docgrid_operational_snapshot_refresh_total{outcome="success"} >= 1`을 기다렸다.
4. 세 Snapshot SQL을 query text의 table과 alias로 식별해 호출 합계가 3임을 확인했다.
5. `pg_stat_statements_reset()`을 실행했다.
6. Python `ThreadPoolExecutor` 20개에서 `/actuator/prometheus`를 300회 호출했다.
7. 모든 응답에 `docgrid_embedding_claimable_jobs`가 존재하는지 확인했다.
8. Snapshot 성공 Counter와 세 SQL 호출 합계를 다시 읽었다.

### 결과와 판정

| 항목 | 결과 | 성공 조건 |
|---|---:|---:|
| 성공 HTTP scrape | 300 | 300 |
| 최초 Snapshot SQL | 3 | 3 |
| scrape 구간 Snapshot 갱신 | 0 | 0 |
| scrape 구간 aggregate SQL | 0 | 0 |
| p50 | 8.404ms | 측정값 기록 |
| p95 | 41.746ms | 측정값 기록 |
| max | 80.882ms | 측정값 기록 |

Snapshot 주기를 10분으로 둔 이유는 Scheduler 실행과 scrape 유발 SQL을 분리하기 위해서다. 이 결과는
“DB Snapshot 갱신 비용이 없다”가 아니라 **“scrape 횟수가 Snapshot SQL 횟수를 증가시키지 않는다”**는
경계를 증명한다. 갱신 한 번의 DB 비용은 별도로 확인한 aggregate SQL 3회다.

## Queue 정체와 Worker 복구

### 목적

실제 DB Queue 상태가 Gauge와 운영 Prometheus 경보를 만들고, Worker가 돌아오면 실제 Embedding 저장과
함께 경보가 해제되는지 검증했다.

### 절차

1. 실제 Backend `/auth/login`에서 격리 DB의 seed ADMIN으로 로그인했다.
2. 실제 multipart `/api/documents` 요청으로 TXT 문서를 업로드해 PENDING Embedding Job을 생성했다.
3. Worker는 끈 채 해당 Job의 `created_at`만 10분 전으로 옮겼다.
4. 동일 업로드의 Outbox Event는 이 실험의 Sync 경보를 섞지 않도록 PROCESSED로 종결했다.
5. 실제 endpoint에서 claimable 1건, active Worker 0개, oldest age 300초 초과를 확인했다.
6. Prometheus가 Backend를 정상 scrape하고 같은 조건을 읽은 UTC 시각을 시작점으로 기록했다.
7. `DocGridEmbeddingWorkersUnavailable`과 `DocGridEmbeddingQueueStalled`의 firing·webhook을 기다렸다.
8. 같은 DB와 Local Storage를 보는 실제 Worker Backend를 시작했다.
9. Job `INDEXED`, 저장 Embedding 1건, claimable Gauge 0을 확인했다.
10. 두 Prometheus 경보 해제와 두 resolved webhook을 확인했다.

### 결과와 판정

| 경계 | 실측 | 설정과의 관계 |
|---|---:|---|
| Worker 부재 firing | 72.228초 | `for: 1m` + 15초 평가 정렬 |
| Worker 부재 webhook | 80.435초 | firing + critical `group_wait: 10s` |
| Queue 정체 firing | 311.594초 | `for: 5m` + 15초 평가 정렬 |
| Queue 정체 webhook | 340.428초 | firing + warning `group_wait: 30s` |
| Worker → INDEXED | 8.598초 | 실제 BGE-M3 호출 포함 |
| Worker → resolved 완료 | 59.715초 | 15초 평가 + test `group_interval: 30s` |
| 최종 Job 상태 | INDEXED | INDEXED |
| 저장 Embedding | 1건 | 1건 이상 |

각 firing 시간은 설정된 `for`보다 짧지 않았다. 최대 약 15초의 차이는 scrape/evaluation 주기의 시작점
정렬에서 발생한다. webhook 시각도 critical과 warning의 서로 다른 group wait 순서를 따랐다.

## Provider 장애와 경보 억제

### 목적

실제 BGE-M3 프로세스 장애가 Prometheus와 Alertmanager를 통과하는지, root-cause 경보가 같은 배포의
파생 warning을 가리고 복구 뒤 resolved를 전달하는지 검증했다.

### 절차

1. BGE-M3 `/health/ready`와 Prometheus `up{job="embedding-provider"}=1`을 확인했다.
2. `docker compose stop embedding-server`로 실제 Provider를 중단했다.
3. `up=0`, `EmbeddingProviderDown` pending·firing, firing webhook 시각을 각각 기록했다.
4. root-cause 경보가 활성화된 상태에서 같은 `cluster/environment`,
   `severity="warning"`, `dependency="embedding-provider"`인 종속 경보를 Alertmanager API에 넣었다.
5. Alertmanager의 종속 경보 상태가 `suppressed`인지 확인했다.
6. warning `group_wait: 30s`보다 긴 35초 뒤에도 종속 firing webhook이 0건인지 확인했다.
7. 종속 입력을 종료하고 실제 Provider 컨테이너를 시작했다.
8. 재할당된 host port를 다시 읽어 readiness, Prometheus 해제와 resolved webhook을 확인했다.

### 결과와 판정

| 경계 | 실측 | 성공 조건 |
|---|---:|---|
| Provider stop → `up=0` | 15.527초 | 60초 이내 |
| Provider stop → firing | 89.811초 | 운영 `for: 1m` 통과 |
| Provider stop → webhook | 99.358초 | firing 뒤 critical group wait 통과 |
| restart → readiness | 5.325초 | 900초 이내 |
| restart → resolved webhook | 54.478초 | 90초 이내(test 설정) |
| 종속 warning | suppressed | suppressed |
| 종속 firing 전달 | 0건 | 0건 |

Provider 중단부터 firing까지의 89.811초에는 첫 실패 scrape 대기, 1분 `for`와 평가 정렬이 포함된다.
firing부터 webhook까지 약 9.5초는 critical `group_wait: 10s`와 일치한다.

## 운영값과 해석 한계

- Prometheus 15초 scrape/evaluation, 모든 `for`, warning 30초·critical 10초 group wait은 운영값이다.
- resolved 실험의 Alertmanager `group_interval`은 운영 5분 대신 30초다. 따라서 54.478초와 59.715초는
  테스트 수치이며 운영 resolved의 최악 시간으로 사용할 수 없다.
- Provider 실험의 root-cause는 실제 BGE-M3 중단에서 발생했다. 종속 warning 하나만 inhibition을 Queue
  대기시간과 분리하기 위해 Alertmanager API로 주입했다.
- HTTP 지연은 단일 arm64 개발 장비의 로컬 loopback 결과다. 절대 성능 목표나 운영 서버 SLA로
  일반화하지 않고, 같은 조건의 회귀 비교 기준으로 사용한다.
- 이 실험은 실제 Slack·Discord 사업자 응답을 검증하지 않는다. Alertmanager가 실제 HTTP webhook
  payload를 보냈고 로컬 receiver가 이를 수신한 경계까지 검증한다.
- 직접 비교할 구형 scrape-time DB 조회 구현이 존재하지 않으므로 임의의 before 수치를 만들지 않았다.
  대신 300회 scrape와 aggregate SQL 0회의 인과 경계를 PostgreSQL 통계로 확인했다.

## 회귀 검증

장애 주입 Harness가 기존 Backend 동작이나 Alertmanager 전달 테스트를 깨지 않았는지 별도로 확인했다.
Backend 테스트는 기존 개발 DB를 사용하지 않고 Drill Compose의 PostgreSQL·Redis 서비스만 고유
Compose project와 새 volume으로 실행했다. Compose가 할당한 loopback port를 `DB_PORT`,
`REDIS_PORT`로 전달했고 테스트 전용 `JWT_SECRET`을 사용했다.

```bash
DRILL_TMP_DIR=/private/tmp/docgrid-backend-tests-338 \
  docker compose -p docgrid-backend-tests-338 \
  -f monitoring/drills/docker-compose.yml up -d --wait postgres redis

DB_HOST=127.0.0.1 DB_PORT=<동적 PostgreSQL port> \
DB_PASSWORD=drill_password \
REDIS_HOST=127.0.0.1 REDIS_PORT=<동적 Redis port> \
JWT_SECRET=<테스트 전용 값> \
  ./backend/gradlew -p backend test --no-daemon --rerun-tasks
```

JUnit XML 197개 suite를 합산한 결과는 다음과 같다.

| 항목 | 결과 |
|---|---:|
| 테스트 | 1,171 |
| 성공 | 1,171 |
| 실패 | 0 |
| 오류 | 0 |
| 건너뜀 | 0 |
| JUnit suite 누적 실행시간 | 214.825초 |
| Gradle 전체 실행시간 | 4분 51초 |

통합 테스트 종료 뒤 테스트 전용 Compose project와 PostgreSQL volume을 삭제했다.

## 추가 검증

```bash
./monitoring/verify.sh --e2e
```

다음 항목이 함께 통과했다.

- Prometheus 설정과 운영 규칙 19개
- Prometheus rule test 3개
- 기본·합성 E2E·Drill·채널 예제 6개를 포함한 Alertmanager 설정 9개
- Drill Python AST와 Shell 문법
- 기본·monitoring·Drill Compose 렌더링
- 실제 Prometheus → Alertmanager → webhook grouped firing·resolved 전달: 각 2초
- Provider root-cause가 먼저 활성화된 뒤 종속 warning이 webhook에 전달되지 않는 inhibition

테스트 과정에서 Docker의 동적 host port가 Provider `stop/start` 뒤 바뀔 수 있음을 발견했다. 초기
Harness는 중단 전 포트를 계속 조회해 readiness를 기다렸고, 재시작 직후 port mapping을 다시 읽도록
수정했다. 수정 후 개별 Provider 실험과 세 시나리오 연속 실행이 모두 통과했다.

추가 monitoring E2E에서는 root-cause와 종속 warning이 같은 평가 시각에 발생하면 Alertmanager 도착
순서에 따라 warning이 먼저 전달될 수 있는 테스트 경합도 재현했다. 종속 warning에 테스트 전용
`for: 2s`를 적용해 root-cause 등록 이후 inhibition을 판정하도록 만들었고, 수정 후 grouped firing,
inhibition, resolved 전달이 모두 통과했다. 운영 경보의 지속 시간이나 routing 설정은 바꾸지 않았다.

closes #338
