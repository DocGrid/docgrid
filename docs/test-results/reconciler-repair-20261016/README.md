# 정합성 점검기(Reconciler) 복구·보고 격리 시험

2026-10-16 KST에 develop `f238caf` JAR, 로컬 격리 PostgreSQL·Valkey·BGE-M3 컨테이너, 시험 문서 4건으로 Reconciler를 시험했다. 공개 서비스, GCP, 기존 로컬 DB와 사용자 데이터는 쓰지 않았다. 새 점검 로직이나 설정 변경 없이 기존 코드를 실행했다.

- 이슈: [#517](https://github.com/DocGrid/docgrid/issues/517)
- 설계: [정합성 점검기 설계](../../design/gimin-%23517-reconciler-consistency-check.md)

**복구 증명 2건(`MISSING_EMBEDDINGS`, `MISSING_JOB`)은 탐지부터 재점검 종결까지 통과했다. 보고 사례 2건(`DELETED_DOCUMENT_RESIDUE`, `STALLED_VERSION`)은 자동 복구 없이 보고만 했다. 단, 정상 삭제된 문서에 `MISSING_EMBEDDINGS`가 남는 기대와 다른 결과 1건을 발견했다. 이 시험에서는 코드를 고치지 않았고, 수정과 재시험은 [#518](https://github.com/DocGrid/docgrid/issues/518)에서 했다.**

## 실행 결과

| 실행 | 목적 | 주입 | 관측 | 판정 |
| --- | --- | --- | --- | --- |
| [run-baseline-01](run-baseline-01/README.md) | 깨끗한 상태 기준선 | 없음 | 버전 2개 점검, Issue 0건 | 통과 |
| [run-missing-embeddings-01](run-missing-embeddings-01/README.md) | Vector 누락 완결 복구 | ACTIVE Vector 1행 삭제 | 탐지 → Event 1 → 새 Job 1 → Worker → `RESOLVED`. 기존 Vector 4행 ID·해시 보존, 누락 1행만 같은 해시로 추가. 검색 1위가 복구 Vector | 통과 |
| [run-missing-job-01](run-missing-job-01/README.md) | Job 누락 완결 복구 | attempt 없는 `PENDING` Job 삭제 | Dispatcher가 재생성하지 않음 → 탐지 → Event 1 → 새 Job → Worker 켠 뒤 `INDEXED`. 검색 1·2위가 시험 문서 | 통과 |
| [run-deleted-residue-01](run-deleted-residue-01/README.md) | 삭제 문서 잔여 보고 | SQL 없음. Dispatcher 끈 채 정상 삭제 | `CRITICAL` 보고, `REPAIR` 0건, 단건 복구 409. 잔여 중 검색 0건. Outbox 처리 뒤 `RESOLVED` | **부분 통과**: 기대와 다른 `MISSING_EMBEDDINGS` 1건 |
| [run-stalled-version-01](run-stalled-version-01/README.md) | 정체 버전 보고와 동시 탐지 | `updated_at` 20분 과거로, 이어서 Job 삭제 | `STALLED_VERSION` `WARNING` → Job 삭제 뒤 `MISSING_JOB`과 동시 탐지, 정체는 `ERROR`로 상향. `REPAIR`는 `MISSING_JOB`만 예약 | 통과 |
| [run-final-check-01](run-final-check-01/README.md) | 최종 점검·무결성 | 없음 | OPEN 1건(위 기대와 다른 결과). Vector·Job 중복 0, 미처리 Outbox 0 | 기록 |

## 기대와 다른 결과

[run-deleted-residue-01](run-deleted-residue-01/README.md#기대와-다른-결과-정상-삭제-뒤-missing_embeddings-보고)에서 삭제 Event가 정상 처리되어 Vector가 모두 STALE이 된 뒤, 삭제된 문서의 현재 버전에 `MISSING_EMBEDDINGS`(`CRITICAL`, `repairable=false`)가 새로 열렸다. 이 Issue는 최종 점검까지 `OPEN`이었다.

코드상으로는 `SyncConsistencyInspector`의 Chunk·Vector 검사가 문서의 `DELETED` 상태를 제외하지 않는 것으로 보인다. 이 결과로 볼 때 정상 삭제된 문서마다 해소되지 않는 Issue가 하나씩 남을 수 있다. 자동 복구 Event는 만들지 않는다. 시험 지시에 따라 이 시험에서는 고치지 않았다. 원인 확인·수정·재시험은 [#518 검증 기록](../deleted-document-false-positive-20261016/README.md)에 있다.

## 환경

| 항목 | 값 |
| --- | --- |
| 실행 위치 | 로컬 macOS arm64, Temurin 17.0.18, Docker Desktop VM 7.75GiB |
| 앱 | develop `f238caf` bootJar, SHA-256 `f86e7da61fe9bfab5641a38a16935ba88468e9b86528e4797735f2c8a8d3057f` |
| DB | `pgvector/pgvector:0.8.1-pg17` 새 컨테이너. PostgreSQL 17.8, pgvector 0.8.1. 127.0.0.1 바인딩 |
| 기타 | Valkey 9.1 새 컨테이너, 저장소 `STORAGE_TYPE=local` 임시 디렉터리 |
| 임베딩 | 기존 `docgrid-embedding-server` 이미지의 새 컨테이너. 모델 캐시 volume을 읽기 전용으로 붙이고 오프라인 모드. BAAI/bge-m3 |
| RAG | `OLLAMA_SERVER_URL`을 닫힌 로컬 포트로 지정. 검색 뒤 RAG는 `FAILED`, 범위 밖 |
| Reconciler 설정 | 모두 기본값: 주기 점검 꺼짐, 배치 100, 정체 임계값 15분. 수동 API만 사용 |
| 계정 | Flyway `V28` 시드 ADMIN 계정(시험 DB 안에만 존재) |
| 문서 | [입력 생성기](inputs/generate_inputs.py)로 만든 가상 주제 TXT 4건, 각 Chunk 5개 |

Backend는 같은 JAR와 DB로 네 단계에 걸쳐 다시 띄웠다. 바꾼 것은 Worker·Dispatcher 켜짐 여부뿐이다.

| 단계 | Worker | Dispatcher | 사용한 실행 |
| --- | --- | --- | --- |
| 1 | 켬 | 켬 | 기준선, 증명 A, 문서 C 인덱싱 |
| 2 | 끔 | 끔 | 문서 C 삭제와 잔여 탐지 |
| 3 | 끔 | 켬 | 잔여 해소, 증명 B 주입·복구 예약, 정체 버전 |
| 4 | 켬 | 켬 | B·D 인덱싱 완료, 최종 점검 |

재현용 스크립트는 [scripts/](scripts/)에, 상태 조회 SQL은 [sql/](sql/)에, 단계별 Backend 핵심 로그는 [logs/](logs/)에 있다. DB 비밀번호와 JWT 비밀값은 실행마다 새로 만들었고 시험 뒤 삭제했다. 증거 파일에는 토큰을 남기지 않았다.

## 해석 범위

- 문서 4건과 Chunk 20개로 한 로컬 실행이다. 실제 운영 데이터, 대량 문서, 동시 부하, A/B 다중 Backend, 주기 점검 경로는 시험하지 않았다.
- 장애는 SQL과 Worker·Dispatcher 정지로 만든 제어된 상태다. 실제 장애 원인을 재현한 것은 아니다.
- `MISSING_CHUNKS`, `MODEL_MISMATCH`, `INVALID_CURRENT_VERSION`, `ORPHANED_DATA`는 이번에 주입하지 않았다.
- 관리 화면은 열지 않았다. 모든 호출은 관리자 REST API로 했다.
- 시간 값은 이 실행에서 관측한 값이며 처리 속도 기준으로 쓰지 않는다.

## 정리

- 단계마다 Backend를 SIGTERM으로 종료했다. Worker는 `graceful=true`로 종료됐다.
- 시험 컨테이너 3개(`docgrid-reconcile-pg/valkey/embedding-20261016`)를 `docker rm -f -v`로 익명 볼륨까지 삭제했고, 남은 컨테이너 0개를 확인했다.
- 임시 저장소 디렉터리, 비밀값 파일, 토큰, 질문 Vector 파일을 삭제했다.
- 기존 컨테이너(`docgrid-postgres17`, `docgrid-embedding` 등)와 다른 시험의 DB는 건드리지 않았다.
