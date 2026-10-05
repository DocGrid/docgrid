# 격리 OpenSQL Dispatcher 최종 코드 재검증

- 실행 ID: `outbox439-isolated-06`
- 완료 시각: 2026-10-06 **00:58 KST** (JVM 종료 로그 기준), 빌드 **55초**
- 목적: 공개 테스트 소스의 개인 식별자를 제거한 최종 코드로 정상 소비와 Retry를 재검증
- 위치: 로컬 Java 17 테스트 JVM → 임시 루프백 SSH 터널 → GCP OpenSQL primary의 별도 시험 DB
- 성공 기준: **2/2 통과**, Event마다 성공 Attempt 1건, 실패 Retry Attempt 보존, 기존 DB 53건 보존

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 보호된 앱·migration·JWT 환경 변수와 전용 DB 이름을 전달해 `./backend/gradlew -p backend test --tests com.opensource.docgrid.opensql.OpenSqlIsolatedOutboxDispatcherIntegrationTest --console=plain --no-daemon` 실행 | JUnit **2/2 통과**, 실패·건너뜀 **0건**, `BUILD SUCCESSFUL`. XML의 테스트 실행 시간 **26.56초** | 최종 공개 테스트 소스의 두 경로 통과 |
| 격리 DB를 별도 읽기 전용 JDBC 세션으로 재조회 | 누적 Event **6건 모두 PROCESSED**, 성공 Attempt **6건**, 의도한 실패 Attempt **3건**, 출처 연결 Job **3건**, 대기 시험 Event **0건** | 3회 정상 소비·3회 실패 후 재처리의 누적 영속 결과. 성공 응답과 Event 완료만으로 외부 GCS 효과를 주장하지 않음 |
| 기존 DB 별도 읽기 전용 JDBC 세션으로 재조회 | `PENDING` **53건** | 기존 Event·Job 보존 확인 |

정확한 시작·종료 시각은 JVM 종료 로그와 JUnit UTC timestamp를 사용해 요약했다. 원본 XML에는 로컬 호스트명이 있어 저장소에 올리지 않았다. 이 파일은 실시간 원문 로그가 아니라 실행 중 콘솔 관측과 종료 후 DB 대조의 비식별 요약이다. 실제 앱 A/B 기동, OpenProxy 경유, GCS 파일 처리, 장애 주입은 이 실행의 범위 밖이다.
