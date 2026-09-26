# SQL-level PREPARE와 pgJDBC 프로토콜 준비문 구분

## 왜 별도로 검증했나

둘 다 “prepared statement”라고 부르지만, 애플리케이션이 `PREPARE/EXECUTE` SQL을 직접 보내는 방식과 pgJDBC `PreparedStatement`가 확장 쿼리 프로토콜로 server-side prepare 하는 방식은 같은 시험이 아니다. OpenProxy transaction pooling에서는 수명·backend 전환 문제를 다르게 해석해야 하므로 별도로 검사했다.

## 명령과 실행 위치

개발자 컴퓨터의 저장소 루트에서 A/B OpenProxy 터널과 앱 계정 환경 변수로 다음 **한 번의 JUnit 명령**을 실행했다. SQL은 Java 코드 안에서 프록시를 지나 GCP의 DB 세션에서 실행됐다.

```bash
./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline
```

| 방식 | Java 실행 위치·API | DB에 보낸 핵심 명령 | 목적 |
| --- | --- | --- | --- |
| SQL-level | 개발자 컴퓨터 JVM의 `Statement`, [`sqlLevelPrepareIsObservedSeparately()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java) | `PREPARE contract_<무작위식별자>(integer) AS SELECT $1::integer` → `EXECUTE contract_<식별자>(41)` → `DEALLOCATE contract_<식별자>` | 앱이 명시적으로 보내는 서버 SQL 명령의 **같은 트랜잭션 내** 동작을 확인한다. 이름은 충돌 방지를 위해 실행 때마다 생성했다. |
| pgJDBC 프로토콜 | 같은 JVM의 `PreparedStatement`와 `PGStatement`, [`preparedStatementsSurviveTransactionPooling()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java) | `SELECT ?::integer` 15회; URL에 `prepareThreshold=5` 또는 `1` | 드라이버가 server-side prepare 단계로 들어가고 반복 결과가 맞는지 확인한다. SQL-level `PREPARE` 문자열을 직접 보내는 시험이 아니다. |

SQL-level 테스트는 `setAutoCommit(false)` 이후 `PREPARE`, `EXECUTE`, `DEALLOCATE`를 **같은 트랜잭션 안에서** 실행해 `41`을 확인하고 `rollback()`했다. 프록시 A 결과와 B 결과가 같은지도 assert했다. 프로토콜 테스트는 같은 `PreparedStatement`를 유지한 채 **매 반복 후 commit**해 15개 트랜잭션을 통과했다.

## 결과·해석

[JUnit 요약](../opensql-contract-evidence/junit-summary.json)에 `CONTRACT_SQL_PREPARE proxy=proxy-a result=same_transaction_pass`와 B의 동일한 결과가 있다. SQL-level 방식은 양쪽 모두 **동일 트랜잭션 안에서** 41을 반환하고 명시적으로 정리됐다. 프로토콜 방식은 A/B × threshold `5/1` 네 조합에서 모두 15회 정확한 정수를 반환하고 `PGStatement.isUseServerPrepare()`가 `true`였다.

따라서 “SQL-level 명령도 동작했다”와 “pgJDBC의 server-side prepare도 해당 반복 조건에서 동작했다”는 두 개의 별도 결론을 낼 수 있다. 전자를 근거로 후자의 캐시 정책을 입증하거나, 후자를 근거로 SQL-level 준비문의 세션 간 보존을 주장하면 안 된다.

## 남은 검증

SQL-level 시험은 **트랜잭션 간 재사용**이나 **다른 backend로 이동한 뒤 재사용**을 해보지 않았다. 프로토콜 반복은 15번의 commit을 거쳤지만 각 조합에서 관측한 backend가 **1개**여서 backend 전환 시 재준비를 검증하지 못했다. `prepared_statements_cache_size`의 일반 TOML `1000`과 관리 풀 값 `0`의 우선순위도 확정되지 않았다. 이 문서의 완료 판정은 두 방식을 구분해 **실제로 수행한 범위의 동작을 각각 기록했다**는 뜻이다.
