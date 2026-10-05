# 격리 OpenSQL Dispatcher 실패 후 재처리

- 실행 ID: `outbox439-isolated-04`
- 완료 시각: 2026-10-06 **00:52 KST** (JVM 종료 로그 기준), 빌드 **38초**
- 목적: 같은 앱 `Clock`으로 Retry 시각을 맞춘 뒤 실제 Claim·Handler·완료가 이어지는지 검증
- 위치: 로컬 Java 17 테스트 JVM → GCP OpenSQL primary의 격리 DB
- 성공 기준: 실패 Attempt **1건**·Retry 예약 후 성공 Attempt **1건**·Event `PROCESSED`

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 시험 Event의 payload 복구 뒤 `LocalDateTime.now(clock).minusSeconds(10)`을 `available_at`으로 지정 | 앱 Claim과 시험 예약이 같은 시각 기준 사용 | 이전 실행의 DB/JVM 시각 차이 제거 |
| `./backend/gradlew -p backend test --tests com.opensource.docgrid.opensql.OpenSqlIsolatedOutboxDispatcherIntegrationTest.retriesFailedEventAfterItsPayloadIsRepaired --console=plain --no-daemon` | 테스트 **1/1 통과**, 실패·건너뜀 **0건**, `BUILD SUCCESSFUL` | 첫 실패와 두 번째 성공을 같은 시험에서 확인 |
| 격리 DB 읽기 전용 대조 | 전체 완료 Event **2건**, 성공 Attempt **2건**, 의도한 실패 Attempt **1건**, 연결 Job **1건**, Retry 시험 Event 최종 `retry_count=1` | 앞선 정상 소비 1건과 이번 재처리 1건의 누적 수치. 중간 실패를 성공으로 지우지 않음 |
| 원래 DB 읽기 전용 대조 | 기존 `PENDING` **53건** | 격리 결과가 기존 Outbox에 섞이지 않음 |

한계: 잘못된 payload를 시험에서 고친 뒤 재처리한 결정적 경로다. 일시적 DB 장애·GCS 실패 후 자동 복구를 재현한 결과는 아니다. 원문 콘솔은 보존하지 않고 결과 수치만 비식별 요약했다.
