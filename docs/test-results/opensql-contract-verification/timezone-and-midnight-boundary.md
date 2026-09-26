# JVM·DB·OS 시간대와 자정 경계 확인

## 검증 목표

개발자 컴퓨터의 JVM, GCP VM/컨테이너의 OS, 프록시를 거친 DB 세션의 시간대가 **같은지 다른지** 먼저 기록했다. 그런 다음 한국 시간 자정 직후의 `timestamp`와 `timestamptz`를 A/B OpenProxy에서 왕복시키고, 한 트랜잭션에서 바꾼 `SET LOCAL TIME ZONE`이 다음 요청에 남지 않는지 확인했다.

## 실제 명령·실행 위치

개발자 컴퓨터의 저장소 루트에서 A/B JDBC URL을 임시 SSH 터널로 주고 `./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline`를 실행했다. 이때 자정 경계 테스트는 같은 JUnit 실행의 5개 테스트 중 하나였다. 별도로 개발자 컴퓨터에서 `bash scripts/opensql/capture_live_ha_contract.sh`를 실행했고, 그 스크립트의 `collect`·`runtime` 모드가 각각 Rocky 컨테이너와 GCP VM 호스트에서 `date +%Z%z`를 호출해 OS 시간대를 비식별화된 문자열로 기록했다.

| 위치 | 실행 코드·SQL | 목적 | 결과 요약 |
| --- | --- | --- | --- |
| 개발자 컴퓨터의 저장소 루트 | `./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline` | A/B 자정 경계·세션 시험을 포함한 계약 JUnit을 실행한다. | 전체 5개 통과·실패 0건; A/B 모두 `boundary=pass`를 기록했다. |
| 개발자 컴퓨터의 저장소 루트 | `bash scripts/opensql/capture_live_ha_contract.sh` | 세 GCP VM 호스트와 Rocky 컨테이너의 시간대를 수집한다. | 노드 3개에서 호스트·컨테이너 모두 `UTC+0000`으로 기록됐다. |
| 개발자 컴퓨터 JVM | `ZoneId.systemDefault()` | 테스트 실행 JVM의 기본 시간대를 확인한다. | `Asia/Seoul`이었다. |
| A/B를 통한 원격 DB 세션 | `SHOW TimeZone` | 실제 세션의 날짜 변환 기준을 확인한다. | A/B 모두 `Asia/Seoul`이었다. |
| 같은 DB 세션 | `SELECT ?::timestamp, ?::timestamptz` | 벽시계 날짜와 시간대가 포함된 순간이 각각 왕복 보존되는지 비교한다. | A/B 모두 지정한 `2026-09-27 00:00:01` 벽시계값과 `+09:00` 순간을 보존해 `boundary=pass`. |
| 같은 DB 세션의 명시적 트랜잭션 | `SET LOCAL TIME ZONE 'Pacific/Honolulu'` → `SHOW TimeZone` → `rollback()` → 다시 `SHOW TimeZone` | 트랜잭션 국소 설정이 풀의 다음 사용으로 새지 않는지 확인한다. | 트랜잭션 안에서는 `Pacific/Honolulu`, rollback 후에는 A/B 모두 원래의 `Asia/Seoul`로 복귀했다. |
| 세 GCP VM 호스트 및 세 Rocky 컨테이너 | `date +%Z%z` | OS 시간대 표기를 수집한다. DB 세션 시간대와 혼동하지 않는다. | 호스트 3대와 컨테이너 3개 모두 `UTC+0000`이었다. |

핵심 Java 코드는 [`timeZoneBoundaryIsStableAcrossProxies()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java)에 있다. 입력은 `LocalDateTime.of(2026, 9, 27, 0, 0, 1)`과 같은 벽시계값의 `OffsetDateTime` `+09:00`이다. `timestamp`는 `LocalDateTime`으로 같은 값인지 비교하고, `timestamptz`는 조회된 `OffsetDateTime`의 **`Instant`가 원본과 같은지** 비교했다. 표시 오프셋 문자열의 동일성이 아니라 순간의 동일성을 검사한 것이다. A/B의 `SHOW TimeZone` 및 서버 버전도 서로 같아야 테스트가 통과한다.

## 관측 결과

| 대상 | 관측 시간대 | 해석 |
| --- | --- | --- |
| JUnit을 실행한 JVM | `Asia/Seoul` | Java 기본 시간대다. GCP VM OS의 시간대와 다르다. |
| proxy-a를 통한 DB 세션 | `Asia/Seoul` | 쿼리를 실행한 DB 세션 시간대다. |
| proxy-b를 통한 DB 세션 | `Asia/Seoul` | A와 같다. |
| GCP VM 3대와 Rocky 컨테이너 3개 | `UTC+0000` | OS 시간대이며 JDBC 세션의 시간대를 강제로 UTC로 만든다는 의미는 아니다. |
| 자정 경계 왕복 | A/B 모두 `boundary=pass` | 지정한 `timestamp` 벽시계값과 `timestamptz` 순간이 보존됐다. |
| `SET LOCAL` 후 rollback | A/B 모두 원래 DB 세션 시간대 복귀 | 이번 트랜잭션 국소 설정은 다음 조회에 남지 않았다. |

출처는 [JUnit 요약](../opensql-contract-evidence/junit-summary.json)의 `CONTRACT_TIMEZONE` 출력, [node1](../opensql-contract-evidence/node1.json)·[node2](../opensql-contract-evidence/node2.json)·[node3](../opensql-contract-evidence/node3.json)의 `container_time_zone` 및 `runtime.host_time_zone`이다. JUnit의 출력은 A/B 각각 `jvm=Asia/Seoul db=Asia/Seoul boundary=pass`였다.

## 결론과 범위

**완료:** 서로 다른 JVM/DB/OS 시간대 조건에서 지정한 자정 경계의 두 PostgreSQL 날짜 유형이 A/B 모두에서 일관되게 왕복했고, `SET LOCAL`이 rollback 뒤 누수되지 않았다. **미검증:** 모든 날짜 값·DST 경계·JSON 직렬화·Spring/JPA 변환·다른 JVM 기본 시간대·네트워크 장애 뒤 재연결 시 세션 시간대. 이번 한 날짜의 통과를 “시간대 문제 전부 해결”로 확대하지 않는다.
