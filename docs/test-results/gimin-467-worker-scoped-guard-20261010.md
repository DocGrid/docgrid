# 시험 문서 버전 한정 Worker 안전장치

## 목적과 범위

GCP 앱 A/B에는 과거 `PENDING` Job과 만료된 `PROCESSING` Job이 남아 있다. Worker를 전역으로 켜면 새 시험 PDF보다 오래된 Job을 먼저 가져가거나 기존 Lease를 회수할 수 있다. 이 변경은 **명시한 단일 `document_version_id`의 Claim·복구만 허용**하는 시험 경로를 추가한다. 필터가 없으면 기존 운영 동작을 유지한다.

## 실행 경계

1. `INDEXING_WORKER_DOCUMENT_VERSION_ID_FILTER`를 지정하지 않으면 전역 Queue 조회가 그대로 동작한다.
2. 필터를 지정하면 `worker-scope-test` 프로필이 없을 때 Worker Executor 생성 단계에서 앱 시작을 거부한다.
3. 필터를 지정한 Worker는 해당 버전의 `PENDING` Job만 Claim하고 해당 버전의 만료 Lease만 조회한다. 다른 Worker를 전역으로 `DEAD` 처리하는 주기는 건너뛴다.
4. 필터는 시험 전용 운영 안전장치이며 삭제 기능이나 일반 운영 Worker의 영구 정책이 아니다.

## 검증

| 실행 ID | 위치 | 결과 | 남은 한계 |
| --- | --- | --- | --- |
| [scope467-unit-r1](evidence/issue-467/scope467-unit-r1.md) | 로컬 JVM | 최종 31건 통과 | Mock 단위 검증 |
| [scope467-db-r1](evidence/issue-467/scope467-db-r1.md) | GCP OpenSQL 경유 | primary SQL 실행 성공, 변경 커밋 0건 | 로컬 DB 통합 테스트는 Docker API 500으로 미실행 |

## 다음 검증

시험 PDF를 먼저 업로드해 실제 문서 버전 ID를 확정한다. 그 ID를 앱 A의 시험 프로필에만 설정한 뒤 Worker를 통제해 `INDEXED`, 청크, 벡터, 검색, 다운로드를 요청·DB·GCS 단위로 대조한다. 앱 B의 Worker는 계속 비활성으로 둔다. 이 실제 E2E는 **아직 실행하지 않았다**.
