# OpenSQL 재연결·Lease 만료 복구 실장애 E2E 검증 결과

## 1. 결과 요약

2026-08-25(Asia/Seoul) 실제 문서 인덱싱 도중 OpenSQL 프로세스만 중단하고 Spring Boot JVM을
유지한 뒤, 같은 OpenSQL을 재기동해 DB 재연결과 Lease 만료 재처리를 검증했다.

최종 판정은 **PASS**다.

- OpenSQL 중단 뒤에도 동일한 Application PID가 유지됐다.
- 재기동 뒤 같은 JVM의 로그인 및 관리자 DB 조회 API가 `200`을 반환했다.
- 만료된 `PROCESSING` Job이 `PENDING`으로 복구된 뒤 두 번째 Attempt에서 `INDEXED`로 완료됐다.
- 실제 BGE-M3 Query Embedding과 pgvector 검색에서 검증 문서가 최상위 결과로 반환됐다.
- 검증 Version의 Chunk·Embedding과 Outbox Event에 중복이 없었다.

## 2. 검증 환경과 실행 경계

| 항목 | 실제 값 |
| --- | --- |
| Git branch / commit | `develop` / `8aeedc9` |
| Application | Spring Boot 3.5.16, Java 17.0.18 |
| Application PID | `54449` — 장애 전·중·후 동일 |
| OpenSQL | Rocky Linux 9.7 x86-64, PostgreSQL 17.8 기반 |
| pgvector | 0.8.1 |
| Embedding Provider | 실제 BAAI/bge-m3 HTTP Server |
| Object Storage | 실제 로컬 MinIO |
| 격리 | 실행 전용 Database·Schema·Bucket |
| Worker 동시성 | 1 |
| Lease | 30초, 갱신 5초, 만료 복구 주기 2초 |

Lease와 복구 주기는 장애 검증 시간을 결정적으로 제한하기 위해 실행 환경 변수로만 단축했다. 제품
Source와 운영 기본값은 변경하지 않았다. 이 결과는 로컬 Docker의 amd64 OpenSQL Container에서 얻은
실장애 복구 결과이며, 공급사 지원 물리 Host 또는 VM에서의 공식 호환성 인증 결과는 아니다.

### 검증 Commit과 제출 Commit의 관계

이 결과는 `8aeedc9`에서 직접 실행한 기록이다. 해당 Commit은 이 문서를 추가하는 이슈 #308의 기준
`develop` Commit보다 앞선 조상 Commit이지만, 그 이후 장애 분류와 Sync 정합성 관련 코드가 변경됐다.
따라서 이 문서를 최종 제출 Commit에서 같은 시나리오를 다시 실행한 결과로 해석하지 않는다.

결과보고서에는 실행 Commit `8aeedc9`를 함께 표시한다. 최종 제출 Commit 자체의 복구 성공을 주장하려면
그 Commit에서 같은 시나리오를 다시 실행하고 별도 결과를 기록해야 한다.

## 3. 실제 실행 타임라인

| 시각(KST) | 관측 결과 |
| --- | --- |
| 01:26:24 | Application PID `54449` 시작 |
| 01:26:25 | Hikari 최초 Physical Connection 생성, OpenSQL 17.8 Migration 성공 |
| 01:28:16.658 | Job 1 첫 Claim, `LOCKED` |
| 01:28:16.818 | `EMBEDDING_STARTED` 커밋 |
| 01:28:17.132 | 장애 직전 `PROCESSING`, Attempt 1, Chunk 32, Embedding 0, Outbox 1 |
| 01:28:18 | OpenSQL만 `pg_ctl -m fast stop`, Application PID 생존 |
| 01:28:18 | Hikari 기존 연결이 SQLSTATE `57P01`·`08006`으로 폐기됨 |
| 01:28:47 | OpenSQL 재기동, 같은 Application PID 생존 |
| 재기동 직후 | 같은 JVM에서 로그인 API `200`, Job 관리자 API `200` |
| 01:28:49.186 | `LEASE_EXPIRED` 기록 |
| 01:28:49.432 | 복구 Scheduler가 `candidates=1, recovered=1` 기록 |
| 01:29:10.065 | Job 1 두 번째 Claim, Attempt 2 시작 |
| 01:30:02.485 | Job·Version·Document `INDEXED`, Chunk 32·Embedding 32 완료 |

OpenSQL 중단 직전 Snapshot은 다음과 같았다.

```text
job=PROCESSING retry=0 claim=true attempts=1 chunks=32 embeddings=0 outbox=1
```

재기동 후 Lease 복구 Snapshot은 다음과 같았다.

```text
job=PENDING retry=1 claim=false lease=null attempts=1 chunks=32 embeddings=0 outbox=1
```

DB 중단으로 기존 Pool Connection이 실제로 제거된 사실은 Application Log에서 확인했다.

```text
HikariPool-1 - Connection ... marked as broken because of SQLSTATE(57P01)
FATAL: terminating connection due to administrator command
HikariPool-1 - Failed to validate connection ... (This connection has been closed.)
```

그 뒤 동일 PID의 로그인과 관리자 조회가 모두 성공했으므로, 재기동된 OpenSQL에 새 Physical
Connection으로 접속했음을 확인했다.

## 4. Lease 복구와 최종 정합성

Job Event 순서는 다음과 같다.

```text
LOCKED
→ PARSE_STARTED
→ CHUNKED
→ EMBEDDING_STARTED
→ LEASE_EXPIRED
→ EMBEDDING_FAILED
→ RETRY
→ LOCKED
→ INDEXED
```

Attempt는 첫 장애 실행 1건이 `FAILED`, 재처리 1건이 `SUCCESS`로 남았다. 최종 DB 판정은 다음과
같다.

| 검증 항목 | 결과 |
| --- | ---: |
| Document 상태 | `INDEXED` |
| current Version 포인터 | 검증 Version과 일치 |
| Document Version 상태 | `INDEXED` |
| Embedding Job 상태 | `INDEXED` |
| Job retry count | 1 |
| Attempt | `FAILED` 1, `SUCCESS` 1 |
| Chunk 수 | 32 |
| Embedding 수 | 32 |
| 서로 다른 Embedding chunk_id | 32 |
| ACTIVE Embedding | 32 |
| Vector 차원 min / max | 1024 / 1024 |

## 5. Vector 검색

인덱싱 완료 뒤 같은 Application에 실제 검색 API를 호출했다.

```text
query=How does SKIP LOCKED prevent duplicate embedding job claims under concurrent workers?
http=200
results=2
target document matches=2
top title=OpenSQL reconnect lease validation 2026-08-25
top similarityScore=0.591167
```

BGE-M3 Query Embedding과 OpenSQL pgvector Cosine 검색을 모두 통과했으며, 장애 복구된 문서가 최상위
검색 결과였다.

`POST /search`가 Vector 검색 응답을 반환한 뒤 별도 비동기 경로로 실행한 Ollama RAG는 로컬 Ollama
연결 실패로 Fallback됐다. 이번 요청의 판정 대상은 Query Embedding과 pgvector 검색까지이며, RAG 답변
생성 성공은 검증 범위에 포함하지 않았다.

## 6. 중복 검증

| 중복 판정 Query | 중복 Group 수 | 결과 |
| --- | ---: | --- |
| `(document_version_id, chunk_index)` | 0 | PASS |
| `(chunk_id, embedding_model_id)` | 0 | PASS |
| Outbox `event_id` | 0 | PASS |
| Outbox `idempotency_key` | 0 | PASS |

검증 Version과 연결된 Outbox Event는 정확히 1건이며 최종 상태는 `PROCESSED`다. 최초 Chunk 32건을
그대로 재사용해 두 번째 Attempt가 Embedding 단계부터 재개됐고, 최종 Embedding도 Chunk별 한 건으로
수렴했다.

## 7. 판정과 제한 사항

요청한 실제 시나리오를 모두 충족했다.

1. Application과 OpenSQL 실제 기동: PASS
2. 실제 HTTP 문서 인덱싱 시작: PASS
3. 처리 중 OpenSQL 프로세스 중단: PASS
4. Application 무중단 PID 유지: PASS
5. OpenSQL 재기동: PASS
6. 같은 JVM의 새 DB 연결과 API 조회: PASS
7. Lease 만료 후 재처리: PASS
8. 최종 `INDEXED`와 Vector 검색: PASS
9. Chunk·Embedding·Outbox 중복 부재: PASS

운영 기본 Lease 5분이 아니라 30초 검증 설정을 사용했으므로, 이 결과는 Lease 시간값 자체가 아니라
만료·복구·재처리 경로의 정합성을 검증한다. 공식 OpenSQL Host 판정이 필요하면 같은 시나리오를 공급사
지원 Rocky Linux 9.7 x86-64 환경에서 운영 Lease 설정으로 한 번 더 실행해야 한다.
