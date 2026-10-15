# `hwp-hwpx-backend-20261015-04`

- 시각: 2026-10-15 22:42:58~22:45:10 KST.
- 위치·개정: 격리 로컬 작업 트리 `feature/505`, PostgreSQL 17·Valkey 컨테이너.
- 목적: 전체 회귀 03에서 관측한 로컬 PostgreSQL 연결 상한 초과를 시험 설정으로 해소하고 기본 백엔드 테스트를 모두 재확인한다.
- 명령: 시험 전용 `JWT_SECRET`, `REDIS_PORT=6381`, `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=4`를 주입한 `./backend/gradlew -p backend test --console plain`. 자격 증명 값은 기록하지 않았다. 애플리케이션의 기본 풀 설정이나 운영 배포 설정은 바꾸지 않았다.
- 합격 기준: 기본 백엔드 테스트 실패 0개. 별도 태그 E2E는 이 명령에서 제외된다.
- 관측: `BUILD SUCCESSFUL in 2m 11s`. JUnit XML 250개 기준 **1,489개 실행, 실패 0개, 5개 건너뜀**. 앞선 네 실패가 재발하지 않았다.
- 판정: **기본 백엔드 전체 회귀 통과**. 로컬 PostgreSQL에 맞춘 시험용 연결 풀 설정이 필요했다. HWP/HWPX의 운영 OpenSQL·GCS 배포 결과를 뜻하지 않는다.
