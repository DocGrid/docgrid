# 통합 시험 첫 실행: 로컬 PostgreSQL 연결 불가

- 실행 ID: `vercel-backend-integration-20261010-01`
- 실행 시각: 2026-10-10 KST. 초 단위 실행 시각은 별도로 보존하지 않았다.
- 위치·대상: 개발 컴퓨터의 Gradle 통합 시험, 당시 사용 불가한 로컬 PostgreSQL.
- 목적·통과 기준: 배포 JAR에 포함된 문서·권한 DB 경로 4건을 통합 시험한다.
- 명령·절차: `DocumentRepositoryFetchTest`, `CollectionUserPermissionGrantIntegrationTest`를 실행했다.
- 관측: 4/4 실패. 공통 선행 오류는 PostgreSQL 연결 거부로, SQL 결과나 권한 판정 단계까지 도달하지 못했다.
- 해석: 코드를 통과시킨 결과가 아니다. 시험 DB가 준비되지 않아 중단된 실행으로 별도 보존한다.
- 후속: 기존 중지된 사용자 컨테이너·데이터는 건드리지 않고, 별도 임시 pgvector 컨테이너에서 같은 시험을 재실행했다.
