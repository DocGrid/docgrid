# `hwp-hwpx-backend-20261015-03`

- 시각: 2026-10-15 22:39:31~22:41:36 KST.
- 위치·개정: 격리 로컬 작업 트리 `feature/505`, PostgreSQL 17·Valkey 컨테이너 준비 뒤.
- 목적: 기본 백엔드 전체 테스트와 PDF/DOCX 포함 회귀 확인.
- 명령: 시험 전용 `JWT_SECRET`, `REDIS_PORT=6381`을 주입한 `./backend/gradlew -p backend test --console plain`. 자격 증명 값은 기록하지 않았다.
- 합격 기준: 기본 테스트 모두 통과. 별도 태그 E2E는 이 명령에서 제외된다.
- 관측: `BUILD FAILED in 2m 4s`. JUnit XML 기준 **1,489개 실행, 4개 실패, 5개 건너뜀**. 실패는 S3 저장소 Context 1개와 최초 업로드 기준선 3개였다. 선행 원인은 PostgreSQL의 `too many clients already`; 같은 Context의 후속 실패가 이어졌다. 실행 후 DB는 정상이며 `max_connections=100`, 조회 시 연결 6개였다.
- 판정: **전체 회귀 미통과**. HWP/HWPX 파서 시험의 실패는 없었다. 여러 Spring Context의 연결 풀 합계가 로컬 DB 상한을 넘은 것으로 보이며, 시험용 연결 풀 상한을 줄여 별도 실행으로 재확인한다.
