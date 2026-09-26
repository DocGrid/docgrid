# 명시적 쓰기 트랜잭션의 primary 도착 확인

## 검증하려는 계약

DocGrid가 두 OpenProxy 중 어느 쪽에 접속해도 **명시적 트랜잭션의 `SELECT → UPDATE`가 쓰기 가능한 primary에서 실행되는지** 확인했다. 이 테스트는 라우팅의 기능 기준선이며, 실제 문서를 수정하거나 쓰기 처리량을 측정하지 않는다.

## 실행 위치와 명령

JUnit은 개발자 컴퓨터의 저장소 루트에서 실행했다. 두 개의 SSH 터널은 각각 기존 GCP node2의 OpenProxy A, node3의 OpenProxy B 서비스 포트로 연결했다. 터널은 원격 접근 수단일 뿐 HA·성능 측정 경로가 아니다. 실제 실행 명령의 테스트 부분은 다음과 같다.

```bash
./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline
```

실행 시 `OPENSQL_PROXY_A_JDBC_URL`, `OPENSQL_PROXY_B_JDBC_URL`, `OPENSQL_APP_USER`, `OPENSQL_APP_PASSWORD`를 **셸 환경 변수**로 제공했다. A/B URL에는 이 수동 시험을 위한 `connectTimeout=5&socketTimeout=15`를 붙였다. 이 값으로 운영 `.env`를 변경했다는 뜻은 아니다. 암호는 공개 로그·Git에 넣지 않았다. 같은 한 번의 JUnit 실행에서 아래 SQL 테스트와 준비문·시간대 테스트 등 총 5개가 함께 돌았다.

| 장소 | 수행 주체 | 수행 내용 | 이유 | 결과 요약 |
| --- | --- | --- | --- | --- |
| 개발자 컴퓨터의 저장소 루트 | Gradle | `./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline` | A/B에 대한 계약 JUnit 5개를 실행한다. | 2026-09-26 12:46 UTC 실행에서 5개 통과·실패 0건. 이 문서의 쓰기 시험은 그중 하나다. |
| 개발자 컴퓨터 JVM | [`routesQueriesByTransactionContract()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java) | A/B마다 JDBC 연결을 만들고 `setAutoCommit(false)` | 명시적 트랜잭션의 라우팅을 분리 측정한다. | 두 프록시의 명시적 읽기·쓰기 경로가 모두 통과했다. |
| A/B OpenProxy를 지난 DB 세션 | JDBC `Statement` | `SELECT pg_is_in_recovery()` | `false`면 현재 SQL이 primary에서 실행됐음을 직접 확인한다. | A/B 모두 `false`를 반환해 `role=primary`로 기록됐다. |
| 같은 트랜잭션의 DB 세션 | JDBC `PreparedStatement` | `UPDATE documents SET id = id WHERE id = ?`에 `-1` 바인딩 | 쓰기 SQL도 같은 트랜잭션에서 허용되는지 확인한다. 존재하지 않는 ID라 0행이어야 한다. | A/B 모두 오류 없이 실행됐고 영향 행은 `0`이었다. 실제 행 변경의 내구성은 검증하지 않는다. |
| 개발자 컴퓨터 JVM | JDBC `Connection` | `connection.rollback()` | 테스트가 운영 데이터를 남기지 않도록 한다. | 두 경로 모두 rollback 호출이 오류 없이 끝났다. 변경 행이 0개였으므로 rollback의 데이터 복구 효과를 별도로 측정한 것은 아니다. |

핵심 코드의 실제 순서는 아래와 같다. 의도를 보여주기 위해 핵심 호출만 발췌했으며 전체 구현은 위 파일을 참조한다.

```java
connection.setAutoCommit(false);
boolean standby = isStandby(connection); // SELECT pg_is_in_recovery()
assertThat(standby).isFalse();
update.setLong(1, -1L);
assertThat(update.executeUpdate()).isZero();
connection.rollback();
```

추가로 같은 JUnit의 [`workerLockingSelectUsesPrimary()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java)는 `embedding_jobs`, `sync_outbox_events`, `rag_responses`에서 `WHERE 1=0 ... FOR UPDATE SKIP LOCKED`를 A/B로 조회했다. 빈 결과이므로 행을 잠그거나 바꾸지는 않는다. standby에서는 `FOR UPDATE`가 허용되지 않으므로 **성공은 primary 라우팅의 간접 증거**다. 이 보조 시험에서는 `pg_is_in_recovery()`를 각 잠금 SQL 안에 넣어 직접 물리 역할을 출력한 것은 아니다.

## 관측값과 해석

[JUnit 요약](../opensql-contract-evidence/junit-summary.json)에 다음 결과가 남았다.

```text
CONTRACT_ROUTE proxy=proxy-a operation=read-write-transaction role=primary
CONTRACT_ROUTE proxy=proxy-b operation=read-write-transaction role=primary
CONTRACT_LOCK proxy=proxy-a table=embedding_jobs route=primary
CONTRACT_LOCK proxy=proxy-b table=embedding_jobs route=primary
```

잠금 조회는 세 테이블 × 두 프록시 모두 성공했다. 명시적 쓰기 트랜잭션 테스트는 두 프록시 모두 `pg_is_in_recovery()=false`, UPDATE 영향 행 `0`, rollback으로 통과했다. JUnit은 2026-09-26 12:46 UTC에 총 5개/실패 0건이었다. `SELECT`를 먼저 보냈을 때부터 트랜잭션이 primary에 도착했으므로, 뒤의 UPDATE가 standby에서 실패할 위험을 이 단순 경로에서는 관찰하지 않았다.

## 판정 경계

**완료:** 양쪽 프록시에서 명시적 JDBC 쓰기 트랜잭션이 primary로 도착하고 무해한 UPDATE가 성공했다. **미검증:** DocGrid의 실제 Hikari·JPA 서비스 메서드 전체, 장애 직후 기존 커넥션 재연결, 대량 쓰기 처리량, 실제 Worker claim부터 commit까지의 흐름. UPDATE가 0행이라 실제 행 변경의 내구성을 증명하지 않는다. 장애나 재시도 없이 성공한 것을 HA 합격으로 부르지 않는다.
