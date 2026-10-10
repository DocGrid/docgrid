# worker469-scoped-r1 — 단일 버전 Worker 실행·DB 대조

- 실행 시각: 2026-10-10 KST, 약 05:02–05:09. 명령별 초 단위 타임스탬프를 캡처하지 않아 구간은 근사값이다.
- 실행 위치: GCP 앱 A만 변경; 앱 B는 변경하지 않음. DB 결과는 앱 A와 같은 pgJDBC 연결을 사용하는 일회성 읽기 전용 조회로 새 primary에서 확인했다.
- 실행 리비전: `d28c202f01251a945410ef6598be8cfb48671d5e`에서 빌드한 시험 JAR SHA-256 `10e3b4776d00c65e9fba32b9194dfb8cc5775d6b5a4074e0cf8c7df9cb3dc744`.
- 목적: 과거 Job 80건을 건드리지 않고 문서 버전 116의 Job 115만 처리한다.
- 성공 기준: 앱 A readiness 200, Job·버전 `INDEXED`, 청크·1024차원 벡터 생성, 중복 0, 이전 Job 지문·Attempt 수 불변, 종료 후 Worker 비활성 복구.

| 순서 | 실행 방법 | 관측 결과 | 해석 |
| --- | --- | --- | --- |
| 1 | 앱 A 기존/새 JAR SHA-256, `INDEXING_WORKER_ENABLED=false`, readiness 확인 | 기존·새 SHA 일치, readiness HTTP 200 | 배포 전 관문 통과 |
| 2 | 기존 JAR·0600 설정 백업 후 `worker-scope-test`, `INDEXING_WORKER_ENABLED=true`, `INDEXING_WORKER_DOCUMENT_VERSION_ID_FILTER=116`을 A에만 적용하고 재시작 | readiness HTTP 200 | 범위 제한 적용 후 기동 |
| 3 | 공개 API의 문서 상태 재조회 | 문서·버전 `INDEXED` | 앱 사용자 경로에서도 완료 관측 |
| 4 | 로컬 `psycopg2` → 임시 IAP 터널 → OpenProxy 직접 조회 | `Client auth not possible`로 실패 | 이 드라이버의 인증 경로로 DB 대조 불가. 성공으로 기록하지 않음 |
| 5 | 앱 A의 배포 JAR에 포함된 pgJDBC를 사용한 일회성 읽기 전용 SQL | primary=true, Job 115 `INDEXED`, retry 0, Attempt 1, 청크 1, 벡터 1, 1024차원 이외 0, 중복 키 0 | 같은 앱 DB 경로로 최종 상태 대조 성공 |
| 6 | 기존 Job 80건의 `(id,status,retry_count)` 튜플 지문과 Attempt 수 재조회 | 시험 전·후 지문 모두 `c1dd438b2cf17a862fdbf4c9f50d954129d3121391a96f56bbef67a095f65bb5`; 기존 Attempt 84→84 | 기존 큐 처리 흔적 없음 |
| 7 | A의 JAR·설정을 백업본으로 복원, 재시작 및 A/B 확인 | A/B 모두 기존 JAR SHA, Worker=false, active, readiness HTTP 200 | 시험 설정 원복 완료 |

완료된 Job의 `locked_by_worker_id`는 남아 있었다. `EmbeddingJob.markIndexed()`가 Claim 소유권 정보를 감사·완료 재생용으로 보존하는 기존 설계와 일치하며, `PROCESSING` lease가 계속 실행 중이라는 의미로 해석하지 않는다.

앱 A의 중복 설정 백업과 일회성 JAR·검증 클래스·드라이버 복사본은 **원복을 확인한 후** 해당 파일만 삭제했다. 계정 비밀번호·JWT·DB 접속 문자열·내부 주소는 기록하지 않았다. Docker 엔진 API 500으로 로컬 PostgreSQL 통합 테스트는 실행하지 못했고, 실제 GCP Job 결과가 이 범위의 실측 근거다.
