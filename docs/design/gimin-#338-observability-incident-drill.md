# 관측성 파이프라인 실제 장애 주입 검증 설계

## 배경

DocGrid는 Backend와 Embedding Provider 메트릭, 비동기 Queue Snapshot, Prometheus 경보 규칙과
Alertmanager 라우팅을 제공한다. 기존 검증은 각 계층의 계약과 합성 시계열 전달을 빠르게 확인했지만,
실제 DB 상태와 Provider 프로세스에서 시작한 신호가 전체 경로를 지나가는지 한 번에 재현하지 않았다.

또한 Queue Gauge는 Prometheus scrape callback에서 DB를 읽지 않고 전용 Scheduler가 만든 Snapshot을
사용하도록 설계됐다. 단위 테스트만으로 이 경계를 확인하면 실제 HTTP scrape와 PostgreSQL 사이에 다른
호출이 추가됐을 가능성을 배제하기 어렵다.

## 목표와 성공 조건

다음 세 시나리오를 하나의 공통 Harness에서 독립적으로 실행한다.

1. 실제 `/actuator/prometheus` 반복 호출 중 운영 Snapshot aggregate SQL이 증가하지 않는다.
2. 실제 문서 업로드로 생성한 Queue가 운영 규칙의 지속 시간 뒤 firing되고 Worker 재개 후 INDEXED와
   resolved 상태로 수렴한다.
3. 실제 BGE-M3 컨테이너 중단이 root-cause 경보로 전달되고 같은 배포의 종속 warning을 억제하며,
   Provider 재시작 뒤 resolved가 전달된다.

각 시나리오는 다음 공통 조건을 만족해야 PASS다.

- 고유 Compose project, 동적 host port와 새 PostgreSQL volume을 사용한다.
- 외부 알림 시크릿 대신 실제 HTTP 요청을 받는 로컬 webhook receiver를 사용한다.
- timeout 안에 관측 상태가 나타나지 않으면 실패하고 진단 로그를 보존한다.
- 실행 종료 시 Backend 프로세스, 컨테이너, DB volume과 로컬 build 이미지를 정리한다.
- 환경·설정·UTC 시각·구간별 시간을 JSON으로 남긴다.

## 구조

```text
실제 Backend ──/actuator/prometheus──┐
                                     ├─ Prometheus ─ Alertmanager ─ local webhook
실제 BGE-M3 ─────────/metrics────────┘
      │
      └─ stop/start 장애 주입

PostgreSQL ─ Queue row / pg_stat_statements
      ▲
      └─ Observer Backend + Indexing Worker
```

공통 Compose에는 PostgreSQL 17 + pgvector, Redis, BGE-M3, Prometheus 3.5.5, Alertmanager 0.33.1과
timestamp를 기록하는 webhook receiver가 포함된다. Backend는 동일한 실행 jar를 host의 서로 다른 동적
포트에서 실행한다. Queue 실험의 Observer와 Worker는 같은 DB와 Local Storage를 공유한다.

## 명령 인터페이스

이 작업은 제품 HTTP API를 추가하지 않는다. 저장소 루트에서 다음 명령을 제공한다.

```bash
./monitoring/drills/run.sh scrape-load [--output-dir PATH]
./monitoring/drills/run.sh queue-recovery [--output-dir PATH]
./monitoring/drills/run.sh provider-outage [--output-dir PATH]
./monitoring/drills/run.sh all [--output-dir PATH]
```

`all`은 세 시나리오를 `scrape-load → queue-recovery → provider-outage` 순으로 실행하지만, 각 시나리오는
자신의 Stack과 데이터를 새로 만든다. 중간 실패 시 뒤 시나리오는 실행하지 않고 실패 JSON과 해당
Stack의 최근 로그를 남긴다.

## 시나리오별 경계

### Scrape와 DB 부하

1. Snapshot 주기를 10분으로 설정해 최초 갱신 뒤 측정 창 안에 예약 갱신이 없도록 한다.
2. `pg_stat_statements`에서 세 aggregate SQL의 최초 호출 합계가 3인지 확인한다.
3. 통계를 reset하고 20개 Thread에서 Management endpoint를 300회 호출한다.
4. 모든 응답에 Queue Gauge가 존재하는지 확인하고 p50·p95·max를 계산한다.
5. 측정 뒤 aggregate SQL 호출 합계와 Snapshot 성공 Counter 변화가 모두 0인지 확인한다.

이 방식은 Scheduler SQL과 scrape 유발 SQL을 구분한다. Snapshot Scheduler를 제거하거나 mocking하지
않으며 실제 Backend와 PostgreSQL을 사용한다.

### Queue 정체와 Worker 복구

1. Worker가 꺼진 Observer Backend에서 실제 ADMIN 로그인과 multipart 문서 업로드를 실행한다.
2. 이 문서의 Job만 10분 전 PENDING으로 옮겨 `oldest age > 300s`를 만든다.
3. Queue 실험에 섞일 수 있는 동일 업로드의 Outbox Event만 PROCESSED로 종결한다.
4. 실제 Gauge와 Prometheus target을 확인한 시점부터 운영 규칙의 `for: 1m`, `for: 5m`을 측정한다.
5. 두 firing webhook 뒤 Worker Backend를 시작한다.
6. Job `INDEXED`, 저장된 Embedding 양수, claimable Gauge 0과 두 resolved webhook을 확인한다.

### Provider 장애와 억제

1. BGE-M3 readiness와 Prometheus `up=1`을 확인한 뒤 컨테이너를 stop한다.
2. `up=0`, pending, 운영 `for: 1m` 이후 firing과 critical webhook을 순서대로 기록한다.
3. 실제 root-cause alert가 Alertmanager에 존재할 때 동일 cluster/environment의 종속 warning 하나를
   Alertmanager API로 주입한다.
4. warning의 상태가 `suppressed`이고 warning용 `group_wait` 30초보다 긴 35초 동안 firing webhook이
   0건인지 확인한다.
5. Provider를 start하고 Docker가 다시 할당한 동적 host port를 조회한다.
6. readiness, Prometheus alert 해제와 resolved webhook을 확인한다.

Provider 중단과 root-cause 경보는 실제다. 종속 warning 주입은 inhibition 자체를 다른 Queue 대기시간과
분리하기 위한 제한된 합성 입력이며 결과 문서에 이 경계를 명시한다.

## 시간 설정

Prometheus의 scrape/evaluation 15초, 모든 운영 규칙의 `for`, Alertmanager `group_wait`은 제품 설정과
같다. `group_interval`만 resolved 전달 실험을 반복 가능한 시간에 끝내기 위해 5분에서 30초로 줄인다.
따라서 firing 측정은 운영 경로를 그대로 나타내지만 resolved webhook 시간은 테스트 설정 결과다.

## 보안과 데이터 경계

- 고정된 로컬 DB 암호와 JWT secret은 격리 Stack에서만 사용한다.
- ADMIN access token은 Python 프로세스 메모리 안에서만 사용하며 command argument와 로그에 쓰지 않는다.
- Slack·Discord·SMTP 자격 증명은 요구하지 않는다.
- 실험용 문서와 DB는 종료 시 삭제한다.
- BGE-M3 모델 cache만 외부 Docker volume에 유지해 반복 다운로드를 막는다.

## 검증 자동화

기존 `monitoring/verify.sh`는 장시간 장애 주입을 CI마다 반복하지 않는다. 대신 다음을 빠르게 검사한다.

- Drill Alertmanager 설정을 고정 버전 `amtool`로 파싱
- Python 실행기 AST 파싱과 Shell 문법
- 동적 mount를 포함한 Drill Compose 렌더링
- 기존 Prometheus 규칙·rule test·Alertmanager 전달 E2E

실제 장시간 실험은 명시적 명령으로 실행하고 결과 JSON을 `docs/test-results/evidence/issue-338/`에
보존한다.

Alertmanager 전달 E2E의 종속 warning은 root-cause가 Alertmanager에 먼저 등록된 뒤 firing되게 해,
동시 도착 순서가 inhibition 검증 결과를 바꾸지 않도록 한다.

closes #338
