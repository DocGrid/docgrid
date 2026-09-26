# OpenSQL Single 장애 복구 관통 검증 Runbook

## 1. 목적

이 문서는 공급사 안내에 따른 Rocky Linux 9.7 x86-64 OpenSQL Single 구성에서 Database 연결 장애 뒤
DocGrid가 같은 Application Process로 다시 연결하고, 만료된 Worker Lease를 재처리해 최종 `INDEXED`와
Vector 검색으로 수렴하는지 검증한다.

이 검증은 다중 Node HA, Primary/Standby 승격 또는 무중단 Failover 검증이 아니다. OpenSQL을 직접
중단·재기동하는 작업은 공급사 절차와 정확한 Test 인스턴스 확인 뒤 실행자가 별도 Terminal에서 수행한다.

## 2. 성공 기준

| 단계 | 성공 기준 |
|---|---|
| 장애 전 | 업로드 Job이 `PROCESSING`이고 Claim Lease가 존재한다. |
| 장애 감지 | `pg_isready`가 OpenSQL 연결 불가를 확인한다. |
| DB 복구 | Application을 재시작하지 않고 관리자 Job API가 다시 응답한다. |
| 작업 복구 | `retryCount`가 증가하고 Job이 최종 `INDEXED`가 된다. |
| 검색 복구 | 고유 Marker 검색 결과에 업로드한 `documentId`가 포함된다. |
| 정합성 | Document·Version·Job이 `INDEXED`이고 Chunk·Embedding·Outbox 중복이 없다. |

하나라도 충족하지 못하면 PASS로 기록하지 않는다. Outbox Event가 실제 DB 장애 시점에 `PROCESSING`이었다는
증거가 없으면, 이 실행으로 Outbox 물리 장애 복구까지 검증했다고 주장하지 않는다. Outbox 처리 실패·Lease
복구는 기존 `SyncDispatchFailureRecoveryIntegrationTest` 결과와 분리한다.

## 3. 안전 경계

- 운영 Database, 공유 개발 Database 또는 다른 팀원이 사용하는 Database에서 실행하지 않는다.
- 공급사 설치 파일, License XML, 다운로드 URL·비밀번호를 Repository나 결과 Log에 포함하지 않는다.
- `OPENSQL_RECOVERY_EXPECTED_DB_NAME`과 실제 Database 이름이 정확히 일치해야 한다.
- `OPENSQL_RECOVERY_CONFIRM=INTERRUPT_ISOLATED_OPENSQL_SINGLE`을 명시적으로 설정한다.
- Script는 OpenSQL 중단·재기동 명령을 실행하지 않는다. 서비스명을 추측하지 않는다.
- 장애 전 Application PID 또는 배포 Instance ID를 별도로 기록하고, 검증 중 Application을 재기동하지 않는다.
- Test 전용 Database Backup 또는 재생성 절차를 확보한 뒤 실행한다.

## 4. 사전 조건

| 항목 | 조건 |
|---|---|
| OpenSQL | 공급사 OpenSQL 17.8, Single 모드 |
| 공식 Host | Rocky Linux 9.7 x86-64 |
| Extension | pgvector 0.8.1 |
| Application | 최종 제출 후보 Commit, Worker·Sync Dispatcher 활성화 |
| 외부 Service | File Storage와 BGE-M3 Embedding Server 정상 |
| CLI | `curl`, `jq`, `psql`, `pg_isready` |
| 계정 | 업로드·검색 사용자 Token과 관리자 Token |
| Test 파일 | PDF, DOCX, TXT 또는 MD이며 고유 검색 Marker 포함 |

먼저 공식 Host 사전 점검을 통과해야 한다.

```bash
scripts/opensql/verify-host.sh
```

## 5. Test 전용 실행 설정

Lease 복구를 짧은 시간 안에 관찰하기 위해 이 실행에서만 다음 값을 사용한다. 운영 기본값을 변경하지 않는다.

```bash
export INDEXING_WORKER_ENABLED=true
export INDEXING_WORKER_MAX_CONCURRENCY=1
export INDEXING_WORKER_LEASE_DURATION=30s
export INDEXING_WORKER_LEASE_RENEWAL_INTERVAL=10s
export INDEXING_WORKER_LEASE_RECOVERY_INTERVAL=5s
export INDEXING_WORKER_DEAD_THRESHOLD=15s
export SYNC_DISPATCHER_ENABLED=true
```

Application을 실행한 뒤 PID 또는 배포 Instance ID를 기록한다. 장애 뒤 같은 값인지 다시 확인한다.

## 6. 고유 Test 문서

다른 문서와 겹치지 않는 Marker를 문서 본문에 넣는다.

```text
DOCGRID_RECOVERY_MARKER_20260822
```

Worker가 `PROCESSING`으로 전환된 뒤 OpenSQL을 중단할 시간을 확보할 수 있도록 Chunk가 여러 개 생성되는
문서를 사용한다. Job이 장애 전에 끝나면 Script가 실패하므로 더 큰 문서로 다시 실행한다.

## 7. 환경 변수 준비

실제 Secret은 Shell Session에만 입력하고 `.env`, Markdown, Shell History와 실행 Log에 기록하지 않는다.

```bash
export APP_URL=<docgrid-base-url>
read -r -s USER_TOKEN
printf '\n'
export USER_TOKEN
read -r -s ADMIN_TOKEN
printf '\n'
export ADMIN_TOKEN

export RECOVERY_TEST_FILE=<absolute-test-file-path>
export RECOVERY_SEARCH_MARKER=DOCGRID_RECOVERY_MARKER_20260822

export OPENSQL_DB_HOST=<test-db-host>
export OPENSQL_DB_PORT=<test-db-port>
export OPENSQL_DB_NAME=<test-database>
export OPENSQL_DB_SCHEMA=<test-schema>
export OPENSQL_DB_USER=<test-user>
read -r -s OPENSQL_DB_PASSWORD
printf '\n'
export OPENSQL_DB_PASSWORD
export OPENSQL_DB_SSLMODE=require
export OPENSQL_INSTALL_MODE=single

export OPENSQL_RECOVERY_EXPECTED_DB_NAME="${OPENSQL_DB_NAME}"
export OPENSQL_RECOVERY_CONFIRM=INTERRUPT_ISOLATED_OPENSQL_SINGLE
```

격리 Network에서 TLS를 제공하지 않는 경우에만 공급사 설정을 확인한 뒤 `OPENSQL_DB_SSLMODE`를 조정한다.

## 8. OpenSQL 제어 Terminal 준비

검증 Script를 시작하기 전에 두 번째 Terminal에 공급사 설치 기록으로 확인한 정확한 중단·재기동 명령을
준비한다. 아래 `<service-name>`을 추측해서 실행하지 않는다.

```text
sudo systemctl stop <verified-opensql-service-name>
sudo systemctl start <verified-opensql-service-name>
```

Docker 기반 Local 기준선이라면 먼저 정확한 Container 이름과 Image를 확인한 뒤 Test Container만
중단한다. 이 결과는 공식 Rocky Host 검증으로 기록하지 않는다.

```bash
docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}'
docker stop <verified-test-container-name>
docker start <verified-test-container-name>
```

## 9. 관통 검증 실행

Repository Root에서 실행한다.

```bash
scripts/opensql/verify-single-recovery.sh
```

Script는 다음 순서로 진행한다.

1. 필수 Command·환경 변수·Single 모드·예상 Database 이름을 검증한다.
2. Test 문서를 업로드하고 `documentId`, `documentVersionId`, `embeddingJobId`를 수집한다.
3. Job이 `PROCESSING`이 될 때까지 기다린다.
4. 중단 안내가 출력되면 두 번째 Terminal에서 OpenSQL을 중단한다.
5. Script가 DB 연결 불가를 확인하고 기본 40초 동안 장애를 유지한다.
6. 재기동 안내가 출력되면 같은 OpenSQL을 시작한다.
7. DB와 같은 Application의 관리자 API 회복을 확인한다.
8. `retryCount` 증가와 최종 `INDEXED`를 기다린다.
9. 고유 Marker로 Vector 검색하고 같은 Document가 반환되는지 확인한다.
10. 실제 OpenSQL에서 상태·중복 정합성 SQL을 실행한다.

Job 처리 시간이 길면 `RECOVERY_INDEXED_TIMEOUT_SECONDS`만 늘린다. Lease와 Retry 불변식을 우회하기 위해
수동 완료 API를 호출하지 않는다.

## 10. PASS 출력 예시

민감정보를 제외한 다음 값만 결과 문서에 옮긴다.

```text
OpenSQL Single 장애 복구 검증 PASS
startedAt=<UTC timestamp>
processingAt=<UTC timestamp>
databaseDownAt=<UTC timestamp>
databaseUpAt=<UTC timestamp>
applicationRecoveredAt=<UTC timestamp>
indexedAt=<UTC timestamp>
jobId=<id>, initialRetryCount=0, finalRetryCount=1
```

정합성 SQL은 Document·Version·Job 상태, Retry 수, Chunk·Embedding 수와 원인 Outbox 상태만 출력한다.
Host, JDBC URL, Username, Token, Password, License 또는 문서 원문은 출력하지 않는다.

## 11. 실패별 해석

| 실패 | 우선 확인 |
|---|---|
| 장애 전에 `INDEXED` | 더 큰 문서 사용, 중단 Terminal 사전 준비 |
| DB 재기동 감지 실패 | 실제 서비스 상태, Port, Firewall, `pg_isready` 대상 |
| DB는 회복했지만 API 실패 | Hikari 새 Connection 생성, JDBC Timeout, Application Log |
| `retryCount` 미증가 | 장애 중 Worker 실행 종료 여부, Lease 만료 시각, Recovery Scheduler 활성화 |
| 최종 `FAILED` | Attempt·Event 오류 코드, 최대 Retry 횟수, Embedding/File Storage 상태 |
| 검색 결과 없음 | Test Marker, 권한, 최소 유사도, 현재 Version·Embedding 상태 |
| Chunk·Embedding 개수 불일치 | 부분 저장 Transaction, Model ID, Retry 멱등성 |

실패가 확인된 경우에만 해당 코드 또는 설정을 최소 범위로 수정하고 같은 Commit에서 다시 실행한다. Hikari를
직접 닫고 다시 만드는 재연결 Service나 모든 쓰기 Transaction에 대한 무조건적인 Retry는 추가하지 않는다.

## 12. 결과 기록 Template

실제 실행 후 이 문서를 덮어쓰지 않고 별도 결과 문서를 `docs/test-results/`에 작성한다.

| 항목 | 기록 값 |
|---|---|
| Commit SHA |  |
| 실행 일시 |  |
| OS·Architecture | Rocky Linux 9.7·x86-64 |
| OpenSQL·pgvector | 17.8·0.8.1 |
| 설치 모드 | Single |
| Application 재기동 여부 | 없음 |
| 장애 지속 시간 |  |
| DB 복구 시간 |  |
| Application API 복구 시간 |  |
| 초기·최종 Retry 수 |  |
| 최종 상태 |  |
| Vector 검색 |  |
| 중복 Chunk·Embedding·Outbox | 0 |
| 최종 판정 | PASS / FAIL |

영상과 결과보고서에서는 이 실행을 `OpenSQL Single 장애 후 자동 작업 복구`로 표현한다. `HA`, `Primary
승격`, `Standby Failover` 또는 완전한 무중단으로 표현하지 않는다.
