# Observability incident drills

DocGrid의 실제 Backend·PostgreSQL·BGE-M3와 운영 Prometheus 규칙을 연결해 관측성 경로를
재현한다. 외부 Slack·Discord 주소 대신 격리된 webhook receiver를 사용하며 각 실행은 고유한
Compose project와 PostgreSQL volume을 만들고 종료할 때 삭제한다.

## 사전 조건

- Docker와 Docker Compose
- Java 17
- Python 3.9 이상
- 최초 BGE-M3 실행에 필요한 모델 다운로드 또는 기존 `docgrid_huggingface-cache` Docker volume

실행기는 캐시 volume이 없으면 생성한다. 모델 파일은 재실행 비용을 줄이기 위해 실험 종료 후에도
보존하지만, PostgreSQL·Prometheus·webhook 결과와 Backend 프로세스는 매번 정리한다.

## 실행

저장소 루트에서 실행한다.

```bash
# 300회 동시 scrape와 PostgreSQL 집계 Query 수
./monitoring/drills/run.sh scrape-load

# 실제 문서 Job의 Queue 경보와 Worker 복구
./monitoring/drills/run.sh queue-recovery

# 실제 BGE-M3 중단·재시작과 종속 경보 억제
./monitoring/drills/run.sh provider-outage

# 세 실험을 순서대로 각각 새 Stack에서 실행
./monitoring/drills/run.sh all
```

기본 결과는 `backend/build/reports/observability-drill/<실행시각>/`에 생성된다. 검토할 경로를
고정하려면 `--output-dir`을 지정한다.

```bash
./monitoring/drills/run.sh all --output-dir /tmp/docgrid-observability-result
```

scrape 횟수와 동시성은 재현 환경에 맞게 조절할 수 있다.

```bash
DRILL_SCRAPE_REQUESTS=1000 \
DRILL_SCRAPE_CONCURRENCY=50 \
./monitoring/drills/run.sh scrape-load
```

## 실험 경계

### `scrape-load`

1. `pg_stat_statements`가 활성화된 실제 PostgreSQL과 Worker가 꺼진 Backend를 실행한다.
2. `OperationalMetricsSnapshotRefresher`의 최초 성공과 aggregate SQL 3회를 확인한다.
3. Snapshot 주기를 10분으로 고정하고 통계를 초기화한다.
4. `/actuator/prometheus`를 기본 20개 Thread에서 300회 호출한다.
5. p50·p95·max와 scrape 구간의 Snapshot 갱신 수, aggregate SQL 호출 수를 기록한다.

Snapshot 갱신을 멈춘 것이 아니라 측정 창보다 긴 정상 설정값을 주입한다. 따라서 결과의 SQL 0회는
scrape callback이 DB를 호출하지 않았다는 의미이며, 주기적 갱신 자체의 비용은 최초 SQL 3회로 별도
확인한다.

### `queue-recovery`

1. Worker가 꺼진 실제 Backend의 로그인·multipart API로 TXT 문서를 업로드한다.
2. 생성된 Job만 10분 전 대기 상태로 옮기고, 이 실험과 무관한 Outbox Event는 종결한다.
3. 실제 Gauge에서 claimable 1건, active Worker 0개, oldest age 300초 초과를 확인한다.
4. 운영 규칙의 `for: 1m`, `for: 5m`을 그대로 사용해 Worker 부재와 Queue 정체 경보를 기다린다.
5. 같은 DB와 Local Storage를 보는 두 번째 Backend Worker를 시작한다.
6. 실제 BGE-M3 호출, Embedding 저장, Job `INDEXED`, Gauge 회복과 두 resolved webhook을 확인한다.

### `provider-outage`

1. BGE-M3 readiness와 Prometheus `up=1`을 먼저 확인한다.
2. 실제 Provider 컨테이너를 중단하고 운영 `EmbeddingProviderDown` 규칙의 pending·firing을 기다린다.
3. 실제 root-cause 경보가 Alertmanager에 있는 동안 동일 배포의 종속 warning 하나를 API로 주입한다.
4. 종속 warning의 `suppressed` 상태와 firing webhook 0건을 함께 확인한다.
5. Provider를 재시작하고 새 동적 host 포트, readiness, Prometheus 해제와 resolved webhook을 확인한다.

종속 warning만 inhibition 경계를 분리하기 위해 직접 주입한다. Provider 중단·복구와 root-cause 경보는
실제 BGE-M3와 운영 Prometheus 규칙에서 발생한다.

## 운영 설정과 다른 값

Prometheus의 scrape 15초, evaluation 15초와 모든 `for` 시간, Alertmanager의 warning 30초·critical
10초 `group_wait`은 운영값을 그대로 사용한다. `group_interval`만 resolved 실험 시간을 제한하기 위해
운영 5분에서 30초로 줄인다. JSON에는 두 값을 모두 기록하므로 테스트 복구 시간과 운영 최악 시간을
구분할 수 있다.

각 결과 JSON은 조건, UTC 시각, 구간별 초 단위 측정값과 검증된 최종 상태를 기록한다. 실패하면 같은
결과 디렉터리에 traceback과 Backend·Compose·webhook 로그를 남기며 Stack은 동일하게 정리한다.
