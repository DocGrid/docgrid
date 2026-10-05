# 격리 OpenSQL Dispatcher 두 경로 결합 회귀

- 실행 ID: `outbox439-isolated-05`
- 완료 시각: 2026-10-06 **00:54 KST** (JVM 종료 로그 기준), 빌드 **51초**
- 목적: 정상 문서 Event 소비와 실패 후 재처리를 같은 테스트 클래스 실행에서 함께 검증
- 위치: 로컬 Java 17 테스트 JVM → GCP OpenSQL primary의 격리 DB
- 성공 기준: 두 시험 모두 통과, Event별 성공 Attempt 1건, 재시도 Event의 실패 Attempt 1건 보존

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| `./backend/gradlew -p backend test --tests com.opensource.docgrid.opensql.OpenSqlIsolatedOutboxDispatcherIntegrationTest --console=plain --no-daemon` | **2/2 통과**, 실패·건너뜀 **0건**, `BUILD SUCCESSFUL` | 두 시나리오가 같은 격리 DB에서 서로의 대기열을 방해하지 않음 |
| 격리 DB 읽기 전용 집계 | 누적 Event **4건 모두 PROCESSED**, 성공 Attempt **4건**, 의도한 실패 Attempt **2건**, 출처 연결 Job **2건** | 반복 실행 간 누적 건수이며 한 번의 push에서 4건을 처리한 뜻이 아님 |
| 기존 DB 읽기 전용 집계 | `PENDING` **53건** 그대로 | 기존 backlog 미소비 |

이후 공개 테스트 코드의 고정 이메일 선택을 없애고 격리 seed의 첫 사용자 ID를 조회하도록 바꿨다. 따라서 이 실행은 최종 수정 전 회귀이며 최종 소스는 다음 실행에서 다시 검증한다. 원본 XML에는 로컬 호스트명이 포함될 수 있어 저장소에 추가하지 않았다.
