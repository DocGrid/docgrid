# 격리 OpenSQL Dispatcher 재시도 첫 실행: 시각 경계 실패

- 실행 ID: `outbox439-isolated-03`
- 완료 시각: 2026-10-06 **00:45 KST** (JVM 종료 로그 기준), 빌드 **38초**
- 목적: Handler 오류 뒤 실패 Attempt와 Retry 예약, 다음 Polling 완료 확인
- 위치: 로컬 Java 17 테스트 JVM → GCP OpenSQL primary의 격리 DB. 기존 DB·앱 A/B는 시험 대상이 아님
- 성공 기준: 첫 Polling 실패 Attempt **1건**·`PENDING` 재예약, 다음 Polling 성공 Attempt **1건**·`PROCESSED`

| 순서·명령/방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 잘못된 payload의 시험 Event 1건 생성 후 실제 Scheduler `poll()` | 첫 Handler 오류 코드 `SYNC-003`, 실패 Attempt **1건**, `retry_count=1`, Event `PENDING` | 실패한 Handler 트랜잭션과 별도의 실패 기록·Retry 예약이 동작 |
| 시험 Event만 올바른 payload로 바꾸고 DB `CURRENT_TIMESTAMP - 1초`로 `available_at` 설정 후 두 번째 `poll()` | Event가 `PENDING`으로 남아 최종 단언 실패. 테스트 **0/1 통과** | 두 번째 Polling은 Event를 다시 Claim하지 못함 |
| DB/JVM 시각을 별도로 읽기 전용 비교 | 관측 시점 DB 시계가 로컬 JVM보다 약 **3초 앞섬** | DB 기준 -1초는 앱 `Clock` 기준으로 아직 미래일 수 있음. 시험 데이터의 시각 기준 오류 |
| 격리 DB에만 남은 실패 시험 Event의 안전 정리 | `isolated-retry:` 접두어·`PENDING`·Job 참조 없음 조건의 Event **1건 삭제**, Attempt는 FK 규칙에 따라 제거 | 다음 반복 시험이 이전 미완료 Event를 잘못 Claim하지 않도록 격리 DB의 시험 데이터만 정리 |

이 실행을 제품의 Retry 결함으로 분류하지 않는다. `SyncEventClaimService`는 앱 `Clock`으로 Claim 가능 시각을 판단하는데 시험 코드만 DB 시각을 사용했다. 실제 운영 앱 A/B와 DB의 시계 차이는 이 실행에서 측정하지 않았다. 콘솔 원문은 저장하지 않고 종료 결과와 읽기 전용 재조회를 비식별 요약했다.
