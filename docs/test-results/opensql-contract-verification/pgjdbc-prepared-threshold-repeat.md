# pgJDBC server-side prepared statement 반복 실행

## 왜 검사했나

pgJDBC의 `PreparedStatement`는 같은 SQL을 반복하면 `prepareThreshold`에 따라 server-side prepare 단계로 전환될 수 있다. OpenProxy의 transaction pooling에서 이것이 실패하면 뒤의 장애·부하 시험에 **HA와 무관한 SQL 오류**가 섞인다. 기본에 해당하는 임계값 `5`와 강제 전환용 `1`을 각각 두 프록시에서 검사했다.

## 어디에서 어떤 명령·코드를 실행했나

개발자 컴퓨터의 저장소 루트에서 A/B JDBC URL 및 앱 계정을 셸 환경으로 주고 아래 명령을 실행했다. A/B URL은 각각 기존 GCP node2/node3의 OpenProxy로 향하는 임시 SSH 터널이었다. 비밀번호 원문은 명령 기록·문서에 쓰지 않는다.

```bash
./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline
```

실제 반복은 [`preparedStatementsSurviveTransactionPooling()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java) 코드 안에서 일어났다. `connect()`가 각 JDBC URL 뒤에 `prepareThreshold=5` 또는 `prepareThreshold=1`을 추가했다. **셸에서 15번 명령을 수동으로 친 것이 아니라**, 같은 물리 JDBC 연결·같은 `PreparedStatement` 객체로 루프를 15번 돌렸다.

```java
connection.setAutoCommit(false);
PreparedStatement statement = connection.prepareStatement("SELECT ?::integer");
for (int value = 1; value <= 15; value++) {
    statement.setInt(1, value);
    ResultSet result = statement.executeQuery();
    // 반환값이 입력값과 같아야 한다.
    connection.commit();
}
```

실제 코드에서는 매번 `SELECT pg_postmaster_start_time()::text || ':' || pg_backend_pid()`로 backend 식별자를 기록하고, 마지막 반복과 같은 트랜잭션 안에서 `SELECT count(*) FROM pg_prepared_statements`를 읽었다. `PGStatement.getPrepareThreshold()`로 요청한 임계값을 확인하고, 루프가 끝난 뒤 `PGStatement.isUseServerPrepare()`가 `true`인지 assert했다. 이 때문에 단순히 SQL 15회 성공했다는 것보다 **드라이버가 준비문 단계에 들어갔다는 증거**가 하나 더 있다.

## 관측 결과

| 프록시 | `prepareThreshold` | 반복·정확성 | 관측 backend 수 | 마지막 backend의 전체 준비문 수 |
| --- | ---: | --- | ---: | ---: |
| A | 5 | 15회, 매번 입력 정수 그대로 반환 | 1 | 22 |
| A | 1 | 15회, 매번 입력 정수 그대로 반환 | 1 | 40 |
| B | 5 | 15회, 매번 입력 정수 그대로 반환 | 1 | 22 |
| B | 1 | 15회, 매번 입력 정수 그대로 반환 | 1 | 40 |

네 조합 모두 `isUseServerPrepare()=true`, `prepared_on_last_backend > 0`을 통과했다. 결과는 [JUnit 요약](../opensql-contract-evidence/junit-summary.json)의 `CONTRACT_PREPARED` 네 줄에 있다. 마지막 준비문 수는 **해당 backend에 보이는 전체 개수**이며 이 테스트 SQL만의 독립 개수가 아니다.

[이전 캐시 카운터 실험](../opensql-contract-evidence/prepared-cache-delta.json)은 A/B 각각 hit `+224`, miss `+80`, eviction `0`을 남겼다. 다만 **이번 최종 JUnit 실행이나 12:57 UTC 설정 스냅샷과 같은 시점의 실행이 아니므로** 위 네 행에 합산하거나 15회 루프의 캐시 적중률로 환산하지 않는다. 설치 TOML의 일반 설정 `prepared_statements_cache_size=1000`과 `SHOW CONFIG`의 `pools.docgrid` 값 `0`도 서로 달라 우선순위를 확정하지 못했다.

## 결론과 제한

**완료:** 두 프록시 × 두 임계값에서 여러 트랜잭션에 걸친 반복 실행·결과 정확성·드라이버 server prepare 진입을 확인했다. **미검증:** transaction pooling이 실제로 *다른 backend*로 전환될 때 named statement를 재준비하는지, OpenProxy 캐시 설정의 적용 우선순위와 캐시 효율, 고동시성에서의 오류율. 이번에는 네 조합 모두 `distinct_backends=1`이므로 “backend 교체를 견뎠다”라고 말할 수 없다. 별도 backend 교체 실험 전까지 이 부분은 후속 위험으로 남긴다.
