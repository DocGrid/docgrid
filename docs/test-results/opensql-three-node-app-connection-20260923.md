# OpenSQL 3노드 DocGrid 접속 준비·검증 — 2026-09-23

> 이 문서는 2026-09-23 당시의 준비 상태를 기록한다. 실제 앱 기동·마이그레이션 결과와 변경된 앱 접속 경로는 [후속 실접속 검증](opensql-three-node-live-app-connection-20260924.md)을 참조한다.

## 범위와 현재 결과

기존 Google Cloud의 동일 영역에 있는 Rocky Linux 9.7 `x86_64` 3노드를 대상으로 했다. 공개 문서에는 프로젝트 ID, 사설 IP, 운영 서버 경로를 기재하지 않는다. 기존 라이선스, 호스트명, VM 사양, 공급사의 `opensql` 프록시 풀은 변경하지 않았다.

| 항목 | 결과 |
|---|---|
| node1/2/3 VM | 3대 모두 `RUNNING`; 사설 IP는 비공개 |
| OpenProxy의 기존 `opensql` 풀 | node2·3 모두 세션 풀링, primary 경로, `postgres` DB에 연결 중 |
| 전용 DB·계정 | node1 리더에 `docgrid` DB와 `docgrid_migrator`, `docgrid_app` 생성 |
| 계정 권한 | DB 소유자 `docgrid_migrator`; `docgrid_app`은 DB 연결 가능, `public` 스키마 CREATE 권한 없음. 신규 객체용 기본 권한 규칙 2개 확인 |
| 새 DB 복제 | 세 노드에서 `docgrid` 조회 성공. `pg_is_in_recovery()`는 node1 `false`, node2·3 `true` |
| 벡터 확장 | 세 노드의 `docgrid` DB에서 `vector` 0.8.1 확인 |
| DocGrid 전용 OpenProxy 풀 | **미적용**. 앱 암호의 보호된 전달에 별도 승인이 필요 |
| DocGrid 애플리케이션·Flyway·E2E | **미실행**. 프록시 풀과 비밀값 전달이 완료되지 않음 |
| 프록시 장애 시 JDBC 전환 | **미실행** |
| 로컬 검증 | Java 테스트 컴파일, 셸 문법, YAML 파싱, `git diff --check` 통과. 환경 변수 없는 HA 테스트는 의도대로 즉시 중단 |

node1에는 root 전용 권한 `0600`인 자격 증명 파일이 생성됐다. 문서·저장소·로그에는 암호를 기록하지 않았다. node1에 임시로 만들었던 앱 암호 사본은 삭제했다. DB와 두 계정은 실제로 생성됐으므로 재실행 시 초기화 스크립트가 이름 충돌을 감지하고 중단한다.

## 구현한 경계

- `application-opensql-ha.yml`은 애플리케이션 연결에 `OPENSQL_APP_JDBC_URL`·`docgrid_app`을, Flyway 연결에 `OPENSQL_MIGRATION_JDBC_URL`·`docgrid_migrator`를 사용한다. 어느 암호도 설정 파일에 하드코딩하지 않는다.
- 앱 연결은 node2·3의 OpenProxy 6432 포트를 다중 호스트 URL로 지정한다. 마이그레이션 연결은 OpenProxy를 통하지 않고 PostgreSQL 5432 포트의 현재 리더를 찾도록 지정한다.
- 새 OpenProxy 풀은 기존 `opensql` 풀을 수정하지 않고 `docgrid` 풀을 추가하도록 준비했다. 초기 설정은 세션 풀링과 primary 라우팅이며, 읽기 분산은 별도 정합성 검증 전까지 활성화하지 않는다.
- `openSqlHaConnectionTest`는 마이그레이션·앱 계정 분리, DB 대상, 쓰기 가능한 리더, 앱 계정의 스키마 생성 권한 부재, 프록시 A·B 개별 접속과 다중 호스트 연결을 확인한다. 일반 `test`에서는 외부 인프라 의존 테스트를 제외한다.

## 연결 및 재검증 절차

비밀값 전달이 승인되고 두 프록시의 전용 풀이 적용된 뒤에만 아래를 실행한다. SSH 터널은 로컬 `127.0.0.1`에만 바인딩한다. 포트 `15432/25432/35432`는 각 PostgreSQL 노드, `16432/26432`는 node2·3의 OpenProxy에 전달한다. 공개 DB 방화벽 규칙은 만들지 않는다.

```text
OPENSQL_MIGRATION_JDBC_URL=jdbc:postgresql://127.0.0.1:15432,127.0.0.1:25432,127.0.0.1:35432/docgrid?currentSchema=public&sslmode=disable&targetServerType=primary&connectTimeout=3
OPENSQL_APP_JDBC_URL=jdbc:postgresql://127.0.0.1:16432,127.0.0.1:26432/docgrid?currentSchema=public&sslmode=disable&connectTimeout=3
OPENSQL_PROXY_A_JDBC_URL=jdbc:postgresql://127.0.0.1:16432/docgrid?currentSchema=public&sslmode=disable&connectTimeout=3
OPENSQL_PROXY_B_JDBC_URL=jdbc:postgresql://127.0.0.1:26432/docgrid?currentSchema=public&sslmode=disable&connectTimeout=3
OPENSQL_MIGRATION_USER=docgrid_migrator
OPENSQL_APP_USER=docgrid_app
```

`OPENSQL_MIGRATION_PASSWORD`와 `OPENSQL_APP_PASSWORD`는 보호된 비밀값으로만 주입한다. 애플리케이션을 시작할 때는 `SPRING_PROFILES_ACTIVE=opensql-ha`를 사용한다. 먼저 `./backend/gradlew -p backend openSqlHaConnectionTest`를 실행하고, 이어서 Spring Boot 기동·Flyway 전체 적용·Hibernate 검증과 핵심 API를 확인한다. 기존 `openSqlVerification`은 테스트 전용 스키마를 생성하므로 앱 계정이 아니라 별도로 승인된 마이그레이션 경로와 외부 MinIO·BGE-M3를 사용해 실행해야 한다. 이후 PDF·DOCX 인덱싱과 권한 검색을 재검증한다.

프록시 장애 시험은 정상 상태에서 두 엔드포인트를 개별 확인한 뒤 A만 중단하고, 동일한 `OPENSQL_APP_JDBC_URL`로 새 연결이 B에 도달하는지 재실행한다. 이때 `OPENSQL_PROXY_A_DOWN=true`를 지정해 A 개별 접속 검사만 제외한다. A를 복구한 후 양쪽 개별 연결을 다시 확인한다. 이 절차는 기존 연결의 무중단 보장이 아니라 **새 JDBC 연결의 전환**을 검증한다.

이전 단일 노드와 이번 3노드 결과는 하드웨어·네트워크가 달라 절대 성능을 직접 비교하지 않는다. 현재 3개 VM은 동일 영역에 있으므로 영역 장애 내성도 검증했다고 주장하지 않는다.
