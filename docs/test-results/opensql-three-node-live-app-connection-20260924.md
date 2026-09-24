# OpenSQL 3노드 애플리케이션 실접속 검증 — 2026-09-24

## 실행 범위

기존 Rocky Linux 9.7 `x86_64` VM 세 대의 OpenSQL 클러스터에서 DocGrid 앱 계정과 마이그레이션 계정을 분리해 실접속했다. 세 VM은 같은 영역에 있다. 프로젝트 식별자, 사설 IP, 서버 경로, 암호는 이 문서에 기록하지 않는다. [전날 접속 준비 결과](opensql-three-node-app-connection-20260923.md)의 미실행 항목을 실제로 검증한 기록이다.

| 항목 | 실행 결과 |
|---|---|
| node2·3 OpenProxy | 기존 `opensql` 풀을 유지하면서 `docgrid` 풀을 각각 추가하고, 원본 설정 백업 후 프록시만 재기동했다. 두 프록시에서 앱 계정 인증을 확인했다. |
| 앱 접속 | PostgreSQL 세 호스트를 나열하고 `targetServerType=primary`를 지정한 JDBC URL로 현재 리더의 `docgrid` DB에 접속했다. |
| 마이그레이션 접속 | 별도 `docgrid_migrator` 계정으로 같은 클러스터의 리더에 직접 접속했다. |
| 권한 경계 | `docgrid_app`은 `public` 스키마 CREATE 권한이 없고, 마이그레이션 계정에는 해당 권한이 있음을 확인했다. |
| JDBC 통합 테스트 | `openSqlHaConnectionTest` 3개 통과: 마이그레이션 경로, OpenProxy 두 진입점 진단, 앱의 3호스트 primary 경로. |
| Flyway·JPA | 실제 DB에 Flyway 마이그레이션 43개를 적용해 v43에 도달했다. 재기동 시 43개 검증 및 추가 적용 없음, Hibernate 엔티티 검증과 Hikari 풀 초기화 성공. |
| HTTP readiness | 앱 기동 후 관리 포트의 readiness가 `UP`을 반환했다. |

## OpenProxy를 앱 경로로 사용하지 않은 이유

두 프록시의 `docgrid` 풀은 `default_role=primary`, 세션 풀링, Patroni 토폴로지 탐지로 설정했다. 그러나 현재 OpenProxy 버전에서 기본 세션 접속이 복제본으로 향하는 것을 실제 `pg_is_in_recovery()`로 확인했다. pgJDBC의 초기 쿼리가 세션을 먼저 복제본에 고정하며, Hikari의 드라이버 검증·격리 수준 조회는 `connection-init-sql`보다 먼저 실행된다. 이 상태에서 역할 지정 SQL을 보내면 PostgreSQL 문법 오류가 발생했다.

진단용 단독 JDBC 접속에서는 `assumeMinServerVersion=17`과 `preferQueryMode=extendedForPrepared`를 지정하고 첫 쿼리로 `SET SERVER ROLE TO 'primary'`를 보내면 두 프록시 모두 리더를 반환했다. 하지만 이 방법은 Hikari 앱 경로에 그대로 적용되지 않는다. 따라서 앱의 쓰기 정합성을 우선해 OpenProxy를 앱 데이터소스에서 제외하고 PostgreSQL 3호스트의 직접 primary 선택 경로를 사용했다. OpenProxy를 통한 앱 고가용성은 **미검증**이다.

## 로컬 접속 설정과 남은 검증

개인 작업 디렉터리의 Git 제외 `.env`를 권한 `0600`으로 두고 `SPRING_PROFILES_ACTIVE=opensql-ha`, 앱·마이그레이션 JDBC URL과 분리된 계정·암호, 프록시별 진단 URL을 추가했다. PostgreSQL 세 포트와 OpenProxy 두 포트는 `127.0.0.1`에만 바인딩한 SSH 터널로 전달했고, 공개 DB 방화벽 규칙은 추가하지 않았다. 앱 계정 암호는 node2·3 OpenProxy 설정에 적용했으며 전송용 임시 사본은 검증 후 삭제했다. 암호 자체는 저장소와 문서에 포함하지 않는다.

이번 실행은 VM·프록시 장애 주입, 실제 PDF·DOCX 인덱싱, 권한 검색, 장시간 성능·복구 측정을 포함하지 않는다. 직접 JDBC 경로의 리더 전환은 별도 장애 시험이 필요하며, 같은 영역의 세 VM으로 영역 장애 내성을 주장할 수 없다.
