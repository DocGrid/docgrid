# 격리 OpenSQL Dispatcher 첫 기동: 필수 환경 변수 누락

- 실행 ID: `outbox439-isolated-01`
- 실행일: 2026-10-06 KST. 정확한 시작·종료 시각은 별도 수집하지 못했다.
- 목적: 별도 시험 DB에서 실제 Polling Scheduler가 문서 버전 Event를 소비하는지 확인
- 위치: 로컬 Java 17 테스트 JVM → 임시 SSH 루프백 터널 → GCP OpenSQL primary의 새 격리 DB. 앱 A/B VM은 실행하지 않음
- 기준: `test/439` 작업 트리, PostgreSQL 17, Flyway와 앱 계정 분리, 파일 저장소만 Mockito 대체
- 성공 기준: Event `PENDING → PROCESSED`, 성공 Attempt 1건, 연결 Job 중복 0건

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 현재 primary·전용 DB 이름 확인 후 DB 생성, `CREATE EXTENSION vector` 및 계정 권한 설정 | 격리 DB 1개 생성, 기존 DB 변경 0건 | 기존 53건을 소비하지 않는 독립 공간 확보 |
| 보호된 `OPENSQL_APP_*`·`OPENSQL_MIGRATION_*` 환경 변수와 `OUTBOX_ISOLATED_DB_NAME`을 전달해 `./backend/gradlew -p backend test --tests com.opensource.docgrid.opensql.OpenSqlIsolatedOutboxDispatcherIntegrationTest --console=plain --no-daemon` 실행 | 빌드 약 **3분 43초**, 테스트 **0/1 통과**. Spring Context가 `JWT_SECRET` 누락으로 시작 실패 | Dispatcher 코드나 DB 소비 실패가 아니라 시험 JVM 설정 실패 |
| primary의 읽기 전용 집계 | Flyway 성공 **46건**, 격리 Outbox **0건** | 시험 메서드는 실행되지 않아 이벤트·Job 생성 없음 |

경고: 원본 Gradle XML에는 실행 호스트 식별자가 포함될 수 있어 저장소에 올리지 않았다. 이 파일은 실시간 원문 로그가 아니라 관측된 종료 결과와 DB 재조회를 비식별 요약한 기록이다. 정확한 시작·종료 시각은 미보존으로 표시한다. 다음 실행에서 기존 로컬 보호 파일의 `JWT_SECRET`을 값 출력 없이 JVM에 전달한다.
