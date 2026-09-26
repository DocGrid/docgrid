# 자동 커밋·read-only·read-write JDBC 라우팅 확인

## 검증 질문

OpenProxy가 모든 SELECT를 primary로 보내는지, 자동 커밋 SELECT와 명시적 read-only/read-write 트랜잭션을 다르게 처리하는지 **실제 SQL 실행 DB의 역할**로 확인했다. 이 구분은 권한 회수 뒤 최신 역할을 읽어야 하는 경로와, 조금 늦어도 되는 조회 경로를 설계할 때 중요하다.

## 실행 명령·위치

개발자 컴퓨터의 저장소 루트에서 `./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline`를 실행했다. A/B JDBC URL은 임시 SSH 터널을 통해 GCP node2/node3 OpenProxy로 갔다. Java 코드는 **개발자 컴퓨터 JVM**에서 실행됐지만 `SELECT pg_is_in_recovery()`는 **프록시를 지난 DB 세션**에서 실행됐다. 이 구별 때문에 “로컬에서 SQL을 실행했다”라고만 표현하면 오해가 생긴다.

[`routesQueriesByTransactionContract()`](../../../backend/src/test/java/com/opensource/docgrid/opensql/OpenSqlOpenProxyContractTest.java)의 실제 세 경로는 아래와 같다.

| 경로 | JDBC 코드/원격 SQL | 왜 이렇게 했나 |
| --- | --- | --- |
| 자동 커밋 SELECT | 기본 auto-commit 연결에서 `SELECT pg_is_in_recovery()` | 단일 조회가 실제 어떤 역할의 DB에 도착하는지 본다. |
| 명시적 읽기·쓰기 | `setAutoCommit(false)` → `SELECT pg_is_in_recovery()` → `UPDATE documents SET id=id WHERE id=-1` → `rollback()` | 쓰기 가능 트랜잭션이 primary에 선제적으로 고정되는지 확인하고 데이터 변경은 남기지 않는다. |
| JDBC read-only | `setReadOnly(true)` → `setAutoCommit(false)` → `SELECT pg_is_in_recovery()` → `rollback()` | read-only 힌트가 primary 일관성을 보장하는지 **가정하지 않고** 측정한다. |

SQL 함수 `pg_is_in_recovery()`가 `true`면 그 SQL이 standby에서 실행된 것이고, `false`면 primary에서 실행된 것이다. 이 결과는 관리 콘솔의 누적 통계보다 해당 요청의 역할을 직접 보여준다.

## 실제 결과

| JDBC 경로 | proxy-a | proxy-b | 해석 |
| --- | --- | --- | --- |
| 자동 커밋 SELECT | standby | standby | 단순 SELECT가 두 프록시 모두에서 읽기 복제본으로 갔다. |
| 명시적 읽기·쓰기 | primary | primary | SELECT 단계부터 primary였고 뒤의 0행 UPDATE가 성공했다. |
| JDBC read-only 트랜잭션 | standby | standby | read-only라는 이유만으로 primary 최신성을 얻지 못했다. |

위 6개 `CONTRACT_ROUTE` 출력은 [JUnit 요약](../opensql-contract-evidence/junit-summary.json)에 그대로 남아 있다. 같은 실행의 `FOR UPDATE SKIP LOCKED` 세 테이블 × 두 프록시도 성공했지만, 그 시험은 잠금 SQL이 허용됐다는 **간접 primary 증거**이고 이 표의 `pg_is_in_recovery()` 직접 측정과는 증거 성격이 다르다.

## 어떤 결론을 내릴 수 있나

이번 설치·설정에서는 자동 커밋 조회와 JDBC read-only 트랜잭션이 standby로 갈 수 있다. 따라서 권한 변경 직후 최신 역할을 반드시 확인해야 하는 로직에 단순히 `@Transactional(readOnly=true)`를 붙이면 안전해진다는 주장은 성립하지 않는다. 다만 **Spring 어노테이션을 붙인 실제 서비스 메서드**를 이 시험에서 호출한 것은 아니다. Spring/Hikari가 트랜잭션 시작 시 언제 연결을 빌리고 어떤 SQL을 앞세우는지는 후속 앱 통합 시험에서 확인해야 한다.

또한 `standby`는 DB 역할만 뜻한다. 각 SELECT가 node2와 node3 중 어느 물리 standby로 갔는지, 두 standby로 균등 분산됐는지, 복제 지연이 얼마였는지는 이 JUnit으로 판정하지 않았다. `SHOW STATS`의 역할별 카운터는 누적값이고 각 테스트 요청과 1:1로 연결되지 않는다. 따라서 이 기준의 **라우팅 역할 확인은 완료**지만 **물리 노드별 로드밸런싱 효과는 미검증**이다.
