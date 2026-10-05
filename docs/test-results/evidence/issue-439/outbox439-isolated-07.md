# 전용 Gradle 태스크로 격리 OpenSQL Dispatcher 최종 검증

- 실행 ID: `outbox439-isolated-07`
- 완료 시각: 2026-10-06 **01:17 KST** (JVM 종료 로그 기준), 빌드 **4분 7초**
- 목적: 기본 테스트에서 분리한 전용 태스크가 새 격리 DB에서 정상 소비·실패 후 재처리를 모두 수행하는지 확인
- 위치: 로컬 Java 17 테스트 JVM → 임시 SSH 루프백 터널 → GCP OpenSQL primary의 재생성된 전용 DB
- 성공 기준: JUnit **2/2**, Event **2건 모두 PROCESSED**, 성공 Attempt **2건**, 의도한 실패 Attempt **1건**, 출처 Job **1건**, 기존 DB Event/Job **53/53**

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 현재 primary·격리 DB 이름 부재 확인 뒤 새 DB 하나 생성, vector와 app/migration 권한 분리 | 생성 성공. 기존 DB 쓰기 **0건** | 첫 시험 DB는 이미 삭제했으며 새 DB에서 태스크 자체를 재현 |
| 보호된 환경 변수로 `./backend/gradlew -p backend openSqlOutboxIsolatedTest --console=plain --no-daemon` | `BUILD SUCCESSFUL` **4분 7초**. JUnit **2/2 통과**, 실패·건너뜀 **0건**, 테스트 본문 시간 **25.404초** | 느린 전체 시간은 새 DB 마이그레이션·기동을 포함. 처리량 성능 수치로 사용하지 않음 |
| 격리 DB에서 별도 SQL 집계 | Event **2건 모두 PROCESSED**, 성공 Attempt **2건**, 실패 Attempt **1건**, 출처 연결 Job **1건** | 정상 1건과 결정적 재시도 1건의 영속 상태 일치 |
| 원래 DB SQL 집계 | `PENDING` Event **53건**, 이를 참조하는 Job **53건** | 기존 백로그 미소비·미삭제 |
| 시험 DB 크기·접속 수 확인 후 해당 DB만 제거, 터널 종료 | DB 약 **11MB**, 활성 연결 **0건** 확인 후 삭제. 최종 격리 DB **0개**, 기존 Event/Job **53/53**, 시험 포트 LISTEN **0개** | 재검증 자원 원복 완료 |

원본 Gradle XML의 로컬 호스트 식별자는 저장소에 넣지 않았다. 이 기록은 실시간 원문 로그가 아니라 실행 중 콘솔 관측과 종료 후 DB 집계를 비식별 요약한 것이다. GCP 앱 A/B, OpenProxy, GCS 파일, primary 장애 주입은 이 실행에 포함되지 않았다.
