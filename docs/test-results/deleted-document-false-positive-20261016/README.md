# 삭제 문서 MISSING_EMBEDDINGS 오탐 수정 검증

**수정 전 단위 시험 2건 실패, 수정 후 같은 시험 5건 통과를 확인했습니다. 전체 백엔드 1,497건 중 실패·오류는 0건이었습니다. 실제 삭제 경로에서도 수정 전 JAR는 오탐을 재현했고, 수정 JAR는 기존 오탐을 닫은 뒤 다시 만들지 않았습니다.**

- 이슈: [#518](https://github.com/DocGrid/docgrid/issues/518) (발견: [#517](https://github.com/DocGrid/docgrid/issues/517))
- 설계: [수정 설계](../../design/gimin-%23518-deleted-document-embedding-false-positive.md)
- 수정 파일: `backend/src/main/java/com/opensource/docgrid/domain/sync/service/SyncConsistencyInspector.java`
- 시험 파일: `backend/src/test/java/com/opensource/docgrid/domain/sync/service/SyncConsistencyInspectorTest.java`

## 1. 환경

| 항목 | 값 |
| --- | --- |
| 실행 위치 | 로컬 macOS arm64, Temurin 17.0.18 |
| 기준 커밋 | develop `f238caf`. 수정과 시험은 커밋 전 작업 트리에서 실행 |
| 실행 시각 | 2026-10-16 16:21:22~16:27:40 KST |
| 전체 빌드 DB | 새 격리 `pgvector/pgvector:0.8.1-pg17`(PostgreSQL 17.8, pgvector 0.8.1)·Valkey 9.1 컨테이너. 사용자 로컬 DB(55432)는 쓰지 않음 |
| 재시험 환경 | 별도 새 PostgreSQL·Valkey·BGE-M3 컨테이너, Backend 1대 |
| 비밀값 | 실행마다 `openssl rand`로 만들고 시험 뒤 삭제. 증거에 토큰 없음 |

## 2. 실행 결과

| 실행 ID | 목적 | 실행·실패·오류·건너뜀 | 판정 | 증거 |
| --- | --- | --- | --- | --- |
| red-01 | 수정 전 서비스 + 새 시험 | 5·2·0·0 | 새 시험 2건 모두 의도한 단언 실패 | [로그](red-01/live.log), [JUnit 요약](red-01/junit-summary.json), [입력 해시](red-01/input-sha256.txt) |
| green-01 | 수정 후 서비스 + 같은 시험 | 5·0·0·0 | 통과 | [로그](green-01/live.log), [JUnit 요약](green-01/junit-summary.json), [입력 해시](green-01/input-sha256.txt) |
| full-build-01 | CI와 같은 `clean build` | 1,497·0·0·5 | 통과. 251개 스위트, 1분 41초 | [로그](full-build-01/live.log), [JUnit 집계](full-build-01/junit-summary.json) |
| [live-deleted-residue-01](live-deleted-residue-01/README.md) | 실제 HTTP 삭제·Outbox 경로 | — | 통과 | 실행 기록 참고 |

- red-01의 서비스 파일 SHA-256 `825aa835…`는 develop `f238caf`의 원본과 같다. green-01에서는 서비스 파일만 `89be062a…`로 바뀌었고, 시험 파일 `890d3aa8…`은 두 실행에서 같다.
- red-01 실패 관찰값
  - 전부 STALE: `MISSING_EMBEDDINGS`, `repairable=false`, `{"activeEmbeddingCount":0,"currentModelEmbeddingCount":5,"allModelEmbeddingCount":5}` — #517 실시험과 같다.
  - 일부 ACTIVE: `MISSING_EMBEDDINGS` **`repairable=true`** 와 `DELETED_DOCUMENT_RESIDUE` 2건. 수정 전에는 삭제 문서를 자동 복구 대상으로 분류했다.
- full-build-01의 건너뜀 5건은 대시보드 A/B 통합 2건과 HA probe PostgreSQL 통합 3건이다. develop에 기록된 직전 전체 CI([run-full-ci-03](../rag-ab-ownership-20261016/run-full-ci-03.md))는 251개 묶음·1,495건·건너뜀 5건이었다. 이번 1,497건은 여기에 새 시험 2건이 더해진 수다.

**명령**

```bash
./backend/gradlew -p backend cleanTest test \
  --tests 'com.opensource.docgrid.domain.sync.service.SyncConsistencyInspectorTest' \
  --no-daemon --console=plain
```

```bash
# DB_HOST·DB_PORT·DB_PASSWORD·REDIS_HOST·REDIS_PORT·JWT_SECRET은 격리 컨테이너 값으로 주입했다.
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=3 ./backend/gradlew -p backend clean build --no-daemon --console=plain
```

재현 스크립트는 [scripts/](scripts/), 상태 조회 SQL은 [sql/](sql/)에 있다.

## 3. 해석 범위

- 바꾼 것은 삭제 문서에서 Vector 완전성 검사(`MISSING_EMBEDDINGS`·`MODEL_MISMATCH`)를 건너뛰는 조건 하나다. 다른 검사는 그대로다.
- 일부 ACTIVE 상태의 삭제 문서는 단위 시험으로만 확인했다. 수정 전 그 상태에서 `REPAIR`가 만들었을 재인덱싱 Event의 실패는 코드 분석이며 실행하지 않았다.
- 주기 점검, A/B 다중 Backend, GCP 환경은 시험하지 않았다.

## 4. 정리

- 전체 빌드용 컨테이너 2개와 재시험용 컨테이너 3개를 `docker rm -f -v`로 삭제했다. 라벨로 찾은 남은 컨테이너는 0개다.
- 임시 저장소, 비밀값 파일, 토큰을 삭제했다. 기존 컨테이너와 사용자 DB는 건드리지 않았다.
