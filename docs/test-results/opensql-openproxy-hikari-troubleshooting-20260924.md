# OpenProxy × HikariCP: 연결은 성공했는데 쓰기는 standby로 간 이유

> 2026-09-24 실행 기록 및 기술 블로그 초안. 공개 가능한 내용만 담았다. VM·프로젝트 식별자, 사설 IP, 비밀번호, 라이선스 파일 및 서버 경로는 제외했다.

## 결과부터

DocGrid의 OpenSQL 3노드 클러스터에는 PostgreSQL primary 1대, standby 2대와 OpenProxy 2대가 있었다. 그러나 처음에는 앱의 JDBC URL이 프록시를 우회해 PostgreSQL 3개 주소에서 primary를 직접 선택했다. 두 프록시가 *설치돼 있다는 사실*과 앱 요청이 *프록시를 통과한다는 사실*은 달랐다.

이번 작업에서는 기존 직접 DB URL을 원복용으로 보존하고, 두 OpenProxy의 `docgrid` 풀을 트랜잭션 풀·SQL 읽기/쓰기 분기 모드로 변경했다. 앱의 `OPENSQL_APP_JDBC_URL`은 두 프록시를 가리키고, Flyway의 `OPENSQL_MIGRATION_JDBC_URL`은 별도 계정으로 DB 3노드 중 현재 primary를 직접 찾는다. 코드의 JDBC URL 하드코딩이나 DB 계정 권한 확대는 없었다.

```text
DocGrid 앱 ─ HikariCP ─ OpenProxy A 또는 B ─ Patroni/OpenSQL 3노드
Flyway ─ 별도 마이그레이션 계정 ─ DB 3노드 중 현재 primary
```

최종 구성에서 외부 클러스터 연결 테스트 5개가 통과했고, 앱은 Flyway 마이그레이션 43개를 두 번 연속 검증하며 기동했다. 두 번 모두 readiness는 `UP`이었다. JPA의 `UPDATE`와 엔티티 `INSERT`도 프록시 경유로 실행했으며 테스트 트랜잭션을 롤백했다.

## 증상: 접속 성공을 쓰기 성공으로 오해했다

초기 `docgrid` 프록시 풀은 `pool_mode="session"`, `default_role="primary"`, `query_parser_enabled=false`였다. 일반 JDBC 연결에서 프록시를 통해 인증·조회가 된다고 해서 앱의 Hikari 연결도 primary에 있다고 볼 수 없었다. Hikari로 실제 연결한 뒤 `pg_is_in_recovery()`를 실행하자 `true`가 나왔다. 즉, 연결은 성공했지만 쓰기 불가능한 standby에 있었다.

기존 진단에서는 단독 JDBC 연결의 **첫 명령**을 `SET SERVER ROLE TO 'primary'`로 보내면 primary에 도달했다. 그러나 앱에서는 Hikari/드라이버가 `connection-init-sql`보다 먼저 초기 검증·메타데이터 조회를 수행했다. 세션 풀에서는 처음 선택된 서버 연결이 클라이언트 세션 동안 유지되므로, 나중에 역할을 지정하는 방식으로는 문제를 고칠 수 없었다. 이 동작은 [이전 실접속 기록](opensql-three-node-live-app-connection-20260924.md)에 남겨 두었다.

공급사도 세션 풀에서 첫 조회가 replica를 선택하면 이후 primary 전용 SQL이 실패할 수 있다고 설명한다. 다만 **왜 현재 빌드에서 `default_role="primary"`가 첫 연결을 primary로 고정하지 못했는지 내부 구현 원인까지 확인한 것은 아니다.** 여기서 확정한 것은 Hikari 연결의 실제 대상과 재현 가능한 실패다. [OpenProxy 읽기 분산 문서](https://docs.tibero.com/tmaxopensql.en/administration/openproxy/load-balancing)

## 가설을 하나씩 검증했다

운영 프록시를 바로 수정하지 않고, 먼저 각 노드에 기존 설정을 복사한 **시험용 OpenProxy**를 별도 포트로 실행했다. 시험 포트는 VM 내부 `127.0.0.1`에만 바인딩하고 로컬 SSH 터널로 접근했다. 기존 프록시와 `opensql` 풀은 그대로 두었다.

| 시험 설정 | 실제 관찰 |
|---|---|
| 기존 세션 풀, SQL 파서 끔 | Hikari 트랜잭션의 대상이 standby였다. node2·3에서 각각 재현했다. |
| 트랜잭션 풀만 적용, SQL 파서 끔 | 여전히 standby였다. 풀 모드 변경만으로는 해결되지 않았다. |
| 트랜잭션 풀 + SQL 파서·읽기/쓰기 분기 + prepared statement 캐시 | Hikari의 명시적 트랜잭션이 primary에 도달했고, `UPDATE`가 성공했다. node2·3의 시험 프록시에서 각각 통과했다. |

적용한 핵심 옵션은 다음과 같다. `prepared_statements_cache_size=1000`은 이번 호환성 시험에서 사용한 값이지, 메모리·성능 최적값으로 확정한 수치는 아니다. 트랜잭션 풀은 서버 연결이 트랜잭션마다 바뀔 수 있으므로 prepared statement 처리에 별도 캐시가 필요하다는 공급사 설명을 따랐다. [OpenProxy 설정 문서](https://docs.tibero.com/tmaxopensql.en/installation/configuration/openproxy), [읽기 분산·prepared statement 제약](https://docs.tibero.com/tmaxopensql.en/administration/openproxy/load-balancing)

```toml
[general]
prepared_statements_cache_size = 1000

[pools.docgrid]
pool_mode = "transaction"
default_role = "primary"
query_parser_enabled = true
query_parser_read_write_splitting = true
```

새 환경에서 DocGrid 풀을 추가하는 저장소의 `scripts/opensql/add-docgrid-openproxy-pool.sh`도 이 설정을 생성하도록 맞췄다. 이 스크립트는 기존 풀이 있으면 중단하므로, 이번에 운영 중이던 두 프록시는 설정 백업 후 별도로 전환·검증했다. 스크립트 자체를 기존 운영 풀에 다시 실행하지는 않았다.

## 운영 경로에 적용한 순서

1. 두 프록시의 운영 설정을 각각 권한 `0600`의 원복용 파일로 백업했다.
2. node2만 변경·재기동하고, Hikari `UPDATE`와 JPA `UPDATE`·`INSERT` 시험을 통과시켰다. 그동안 node3 운영 프록시는 그대로 유지했다.
3. 같은 절차를 node3에 적용하고 다시 시험했다. 기존 `opensql` 풀은 세션 모드로 유지했다.
4. 개인 `.env`의 앱 URL만 두 프록시 주소로 전환했다. 기존 직접 DB URL은 `OPENSQL_APP_DIRECT_JDBC_URL`에 원복용으로 남기고, Flyway URL은 변경하지 않았다. `.env`는 Git 제외·권한 `0600`이다.
5. 시험용 프록시 프로세스와 비밀번호가 들어 있는 시험 설정 사본은 제거했다. 운영 설정과 원복 백업은 유지했다.

앱 URL의 형태는 아래와 같다. 주소는 설명용 별칭이며 실제 호스트·IP는 공개하지 않는다. `loadBalanceHosts=true`는 **새 JDBC 연결**의 프록시 선택을 분산하는 옵션이다. SQL 요청마다 절반씩 나누거나 DB standby 읽기 분산을 보장하는 옵션은 아니다. [pgJDBC 다중 호스트 문서](https://jdbc.postgresql.org/documentation/use/)

```text
OPENSQL_APP_JDBC_URL=jdbc:postgresql://proxy-a:6432,proxy-b:6432/docgrid?loadBalanceHosts=true
OPENSQL_MIGRATION_JDBC_URL=jdbc:postgresql://db-1:5432,db-2:5432,db-3:5432/docgrid?targetServerType=primary
```

## 해결됐다는 근거와 아직 아닌 것

- 최종 설정의 외부 클러스터 테스트: 5개 통과. Flyway 전용 계정, 두 프록시 개별 경로, Hikari 쓰기, 앱 다중 프록시 URL, JPA 쓰기 경로를 검사했다.
- JPA 테스트에서는 `pg_is_in_recovery()=false`를 확인한 트랜잭션에서 `UPDATE`와 `FailoverEvent` 엔티티 `INSERT`를 실행했다. 테스트 행은 롤백했다. PostgreSQL 시퀀스 값은 롤백되지 않아 증가할 수 있다.
- Flyway는 기존 마이그레이션 43개를 두 차례 검증했고 추가 적용은 없었다. 각 기동에서 Hikari 풀과 Hibernate 엔티티 검증을 통과하고 readiness `UP`을 확인했다.
- 실행 중인 앱 프로세스의 TCP 연결을 확인했을 때 Hikari 연결 5개가 모두 OpenProxy 터널로 향했다. 한 시점의 분포는 프록시 A 1개, B 4개였다. 이는 **앱이 프록시를 통과했다는 증거**이지 부하가 균등하거나 두 standby가 조회를 나눴다는 증거는 아니다.
- 프록시를 실제 중단하는 대신, JDBC URL의 첫 로컬 엔드포인트를 닫힌 포트로 지정했다. 새 JPA 연결은 두 번째 프록시를 통해 쓰기에 성공했다. **기존 연결의 무중단 전환이나 실제 프록시 프로세스 장애는 아직 시험하지 않았다.**

이번 검증은 로컬 앱에서 SSH 터널을 통해 GCP 내부 프록시에 접속해 진행했다. 검증 후 로컬 앱과 터널은 종료했다. 따라서 개인 `.env`의 `127.0.0.1` 프록시 URL만으로 앱이 상시 접속 가능한 상태는 아니다. 다시 실행할 때는 안전한 터널을 열거나, 앱을 같은 VPC의 실행 환경에 배치하고 내부 프록시 주소를 설정해야 한다. DB·프록시 포트를 인터넷에 공개하는 방식은 사용하지 않았다.

이 작업은 *앱의 쓰기 경로를 OpenProxy로 옮긴 것*까지다. PDF·DOCX 인덱싱, 권한 검색·RAG, 복제 지연 중 권한 회수, 리더·프록시 장애 주입, standby 읽기 분산과 성능 수치는 별도 검증이 필요하다. 특히 공급사 문서는 명시적 트랜잭션을 primary로 라우팅한다고 설명하므로, Spring의 `@Transactional(readOnly=true)`만으로 읽기가 standby로 분산됐다고 주장할 수 없다. 세 VM이 같은 영역에 있어 영역 장애 내성도 주장하지 않는다.

## 블로그용 결론

이번 장애는 “DB 접속이 된다”를 “앱이 올바른 DB 역할에 연결됐다”로 오해할 때 생겼다. OpenProxy 두 대가 설치돼 있었지만 앱은 프록시를 우회했고, 프록시로 URL을 바꿔 본 초기 시험에서는 Hikari 연결이 standby에 남았다. 단독 JDBC의 역할 지정 성공도 Spring 앱의 초기화 순서를 대신 검증해 주지 못했다.

해결은 URL 한 줄이 아니라 **프록시 풀 모드·SQL 라우팅·prepared statement 처리와 애플리케이션 트랜잭션을 함께 검증하는 과정**이었다. 시험용 루프백 프록시에서 실패를 재현하고 옵션을 하나씩 바꾼 뒤, 두 운영 프록시를 순차 적용했다. 마지막에는 JPA 쓰기와 앱 재기동, 실제 TCP 연결 대상까지 확인했다. 다음 과제는 이 경로에서 장애를 주입하고, 문서 권한을 지키면서 얼마나 빨리 회복하는지를 수치로 보여주는 것이다.
