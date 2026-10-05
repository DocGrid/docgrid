# 격리 OpenSQL Dispatcher 정상 소비

- 실행 ID: `outbox439-isolated-02`
- 완료 시각: 2026-10-06 **00:41 KST** (JVM 종료 로그 기준), 빌드 **44초**
- 목적: 최초 업로드의 `DOCUMENT_VERSION_CREATED` Event가 실제 Dispatcher에서 완료되는지 검증
- 위치: 로컬 Java 17 테스트 JVM → 임시 SSH 루프백 터널 → GCP OpenSQL primary의 별도 시험 DB
- 기준: `test/439` 작업 트리, Flyway **46건**, 파일 저장 호출은 대체 객체
- 성공 기준: Event `PENDING → PROCESSED`, 성공 Attempt **1건**, 같은 Event에 연결된 Job **1건**

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 보호된 `OPENSQL_*`·`JWT_SECRET`을 환경 변수로만 전달해 `./backend/gradlew -p backend test --tests com.opensource.docgrid.opensql.OpenSqlIsolatedOutboxDispatcherIntegrationTest --console=plain --no-daemon` 실행 | 테스트 **1/1 통과**, 실패·건너뜀 **0건**, `BUILD SUCCESSFUL` | 실제 `SyncEventPollingScheduler.poll()` 호출 경로 통과 |
| 업로드 트랜잭션 → Event/Job 생성 → Dispatcher Claim·Handler·완료 | 격리 DB Event `PROCESSED` **1건**, 성공 Attempt **1건**, 연결 Job **1건** | 처리 상태뿐 아니라 출처 Job의 중복 부재까지 DB로 대조 |
| 기존 DB 별도 읽기 전용 집계 | `PENDING` **53건** 그대로 | 기존 Job 53건과 원본 Outbox는 소비·삭제하지 않음 |

한계: 로컬 JVM이 GCP DB에 연결한 시험이지 GCP 앱 A/B의 Dispatcher 기동은 아니다. 파일 저장은 대체 객체여서 GCS 객체 생성 증거도 아니다. 이 실행에서는 장애·재시도를 시험하지 않았다. 원본 콘솔 스트림 대신 결과·DB 수치만 비식별 요약했고, 비밀값은 기록하지 않았다.
