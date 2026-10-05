# 격리 OpenSQL Outbox 자동 Polling 실행 기록: outbox439-isolated-08

- 실행 시각: 2026-10-06 01:49~01:54 KST
- 목적: 테스트가 `poll()`을 직접 호출하지 않아도 실제 `@Scheduled` 주기가 새 Outbox Event를 소비하는지 확인
- 위치: 로컬 Java 17 테스트 JVM → 임시 SSH 터널 → GCP OpenSQL 현재 primary의 새 격리 DB. GCP 앱 A/B VM은 사용하지 않음
- 시험 범위: 별도 시험 DB, Flyway 46건, 파일 저장 호출은 테스트 대체 객체. DB 식별자·계정·주소는 기록하지 않았고 기존 DB의 53건은 변경하지 않음
- 성공 기준: 자동 Polling 1건과 수동 정상·재시도 2건 통과, Event 3건 `PROCESSED`, 성공 Attempt 3건, 의도한 실패 Attempt 1건, 연결 Job 2건, 원래 DB Event/Job 53/53, 시험 DB와 터널 제거

| 실행 위치·명령 또는 방법 | 관측 결과 | 결과 해석 |
| --- | --- | --- |
| 현재 primary에서 격리 DB 생성, `vector` 확장과 앱·migration 계정 권한 설정 | 새 시험 DB에서 Flyway **46건** 적용 | 기존 DB가 아닌 독립 스키마를 사용 |
| 로컬에서 격리 DB·DB 계정·JWT 비밀을 보호된 환경 변수로 제공하고 `./backend/gradlew -p backend openSqlOutboxIsolatedTest --console=plain --no-daemon` 실행 | `BUILD SUCCESSFUL` (**4분 56초**). 전용 태스크 **3/3 통과**, 실패 0·건너뜀 0 | 기존 수동 `poll()` 2건과 신규 자동 `@Scheduled` 1건을 함께 검증. 보호값은 출력·기록하지 않음 |
| 자동 Polling 시험에서 문서 업로드 후 `poll()` 직접 호출 없이 최대 30초 Await | 자동 시험 **1/1 통과**, Event `PROCESSED`; 해당 Event의 성공 Attempt **1건**, 출처 Job **1건** | 실제 Spring 스케줄러가 격리 DB의 새 이벤트를 처리함. 클라우드 앱 VM 자동 실행의 증거는 아님 |
| 테스트 종료 후 현재 primary에서 격리 DB 상태별 개수 조회 | Event **3/3 PROCESSED**, 성공 Attempt **3**, 의도한 실패 Attempt **1**, 출처 Job **2** | 한 실패 Attempt는 수동 재시도 시험의 중간 상태이며 최종 Event는 처리 완료. 실패 payload 시험 Event에는 Job이 없음 |
| 원래 DB 읽기 전용 집계 | `PENDING` Event **53**, 그 Event를 참조하는 Job **53** | 기존 백로그·외래키 관계 변경 없음 |
| 삭제 전 DB 크기·세션 확인 → 정확한 시험 DB만 `DROP DATABASE` → 재조회 | 대상 **11MB**, 접속 **0개** 확인 후 `DROP DATABASE` 성공; 해당 DB **0개** | 이번 시험 자료만 제거. 원래 DB는 삭제 대상이 아님 |
| SSH ControlMaster 종료·시험 임시 디렉터리 제거 | 터널 종료 응답, 임시 `known_hosts`와 디렉터리 제거 | 원래 SSH 키·VM 메타데이터·앱 A/B 설정은 변경하지 않음 |

Gradle의 원본 JUnit XML에는 로컬 호스트 정보가 포함될 수 있어 저장소에 넣지 않았다. 테스트별 `tests=2`와 `tests=1`, `failures=0`, `errors=0`, `skipped=0`을 로컬 XML에서 확인한 뒤 위 비식별 집계만 기록했다. 콘솔은 실행 중 확인했지만 비식별 원문 스트림을 별도 실시간 로그 파일로 수집하지는 못했다. 원래 DB의 Dispatcher 설정, OpenProxy 경유, GCS 실파일, 재인덱싱·soft delete 부작용, primary 장애 수렴은 이 실행의 판정 범위가 아니다.
