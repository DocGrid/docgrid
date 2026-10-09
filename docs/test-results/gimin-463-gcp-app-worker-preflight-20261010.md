# GCP 앱 A/B·인덱싱 Job 안전 관문 (2026-10-10)

- 이슈: #463
- 실행 범위: 기존 GCP 앱 2대의 읽기 전용 설정 확인, OpenProxy를 경유한 OpenSQL Job 집계
- 판정: **앱 배포 일치 PASS / 전체 Worker 활성화 NO-GO**
- 비밀·내부 주소·프로젝트 ID·사용자 식별자는 수집하지 않았다.

## 목적과 사전 완료 조건

CPU 임베딩 서버 연결과 인덱싱 Worker 활성화 전에 두 앱이 같은 배포를 사용하고, 실제 설정이 무엇인지 확인한다. 기존 Job이 예상치 않게 재처리되지 않도록 `PENDING`과 만료 `PROCESSING`을 읽기 전용으로 분류한다. 이 문서의 관측은 Worker 활성화나 문서 E2E의 성공을 뜻하지 않는다.

## 실행별 증거

| 실행 ID | 실행 위치·방법 | 관측 결과 | 해석 |
| --- | --- | --- | --- |
| `app463-inventory-r1` | GCP 앱 A/B의 IAP SSH에서 서비스 상태, JAR SHA-256, 선택 설정을 값 제한 방식으로 조회 | A/B 모두 `active`; JAR SHA-256 동일; `INDEXING_WORKER_ENABLED=false`, `STORAGE_TYPE=gcs`, `SYNC_DISPATCHER_ENABLED=false`, `EMBEDDING_SERVER_URL` 미설정 | 배포 바이트는 같지만 어느 Git 커밋으로 빌드됐는지는 이 해시만으로 확정하지 못한다. 임베딩 URL 미설정 상태에서 앱이 CPU VM으로 요청한다고 주장할 수 없다. [원본 요약](evidence/issue-463/app463-inventory-r1.md) |
| `job463-initial-r1` | 로컬 읽기 전용 PostgreSQL 클라이언트 → 만료형 IAP 터널 → 앱 A → OpenProxy | `read_only=on`; PENDING 5, PROCESSING 2, FAILED 21, INDEXED 51 | 첫 관측. 정확한 명령 시각을 독립 수집하지 못했고, 읽기 분산 대상 역할도 기록되지 않아 최종 판정 기준으로 쓰지 않는다. |
| `job463-followup-r1` | 동일 경로에서 문서 상태·claim 가능 여부 확인 | PENDING 6 / claim 가능 6; PROCESSING 2 / 만료 lease 2. PENDING 문서 6개는 삭제되지 않은 `UPLOADED`, PROCESSING 2개는 삭제된 문서 | 첫 관측과 PENDING 수가 다르다. 동시 업로드 또는 replica 시차 중 무엇이 원인인지 이 실행만으로 구분하지 않는다. |
| `job463-role-samples-r1` | `pg_is_in_recovery()`와 상태별 건수를 **같은 SQL**로 3회 조회 | 3회 모두 standby에서 PENDING 6, PROCESSING 2, FAILED 21 | 최종 집계가 standby에서 반복 재현됐다. primary의 같은 시점 절대 최신 상태를 보장하지 않는다. |
| `job463-canonical-r1` | 같은 경로, `read_only=on` 확인 후 KST 시각을 붙여 상태·문서·claim 가능 집계 | 2026-10-10 04:30:48–04:30:51 KST; standby에서 FAILED 21, INDEXED 51, PENDING 6, PROCESSING 2; 즉시 claim 가능 6, 만료 lease 2 | Worker가 켜질 때 기존 6건의 처리와 2건의 lease 복구 가능성을 배제할 수 없다. [원본 요약](evidence/issue-463/job463-canonical-r1.md) |

## 코드에 따른 영향 판정

`WorkerJobPollingScheduler`는 Worker가 켜지면 빈 슬롯에서 `EmbeddingJobClaimService.claim()`을 반복 호출한다. `EmbeddingJobRepository.findNextPendingForUpdate()`는 사용자나 시험 문서 필터 없이, 즉시 실행 가능한 PENDING을 우선순위·생성 시각 순서로 가져온다. `WorkerLeaseRecoveryScheduler`도 만료 PROCESSING을 검색한다. 따라서 **시험 PDF 한 개만 처리한다는 가정으로 A/B의 Worker를 켜면 안 된다.** 이는 코드와 현재 DB 상태로부터의 위험 추론이며, 이 실행에서 Worker를 실제 활성화한 결과는 아니다.

## 다음 단계 관문

1. CPU VM의 저장소 기반 새 이미지와 앱 A/B의 내부 연결을 확인한다. 이때 Worker는 계속 `false`로 둔다.
2. 공개 E2E를 위한 Worker 활성화 방식은 과거 6+2건의 처리 여부를 명시적으로 통제해야 한다. 기존 Job을 삭제·상태 변경하거나 전체 큐를 몰래 실행하지 않는다.
3. 다음 시험 직전에 primary 기준 최신 상태를 재조회한다. 위 standby 스냅샷은 시간이 지난 뒤의 작업 범위를 보장하지 않는다.

## 정리·한계

- 조회는 `default_transaction_read_only=on`으로 수행했다. DB 행, 앱 설정, GCS 객체를 바꾸지 않았다.
- 앱 A/B의 SSH 키와 로컬 터널은 만료형이다. 이 문서 작성 시점에는 다음 연결 시험에 재사용할 예정이며, 작업 종료 시 회수 결과를 별도로 확인한다.
- 앱 JAR SHA-256은 저장했지만 Git 커밋 매핑은 미확정이다. Worker·검색·다운로드·WebSocket E2E는 이 시험에서 실행하지 않았다.
