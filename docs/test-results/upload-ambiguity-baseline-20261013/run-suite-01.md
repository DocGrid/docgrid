# 실행 로그: 세 시나리오 연속 실행

- 실행 ID: `20261013-suite-01`
- 시각: CSV 생성 2026-10-13 21:40:04 KST
- 목적: 같은 테스트 클래스에서 Mockito 상태와 DB 정리가 시나리오 사이에 간섭하지 않는지 확인
- 위치·환경: 격리 작업 트리, 로컬 임시 PostgreSQL 17·pgvector 0.8.1
- 명령: `JWT_SECRET="$(openssl rand -hex 32)" UPLOAD_BASELINE_RUN_ID=20261013-suite-01 ./backend/gradlew -p backend test --tests '*UploadAmbiguityBaselineIntegrationTest' --offline --console=plain`
- 사전 성공 기준: 세 메서드 모두 통과, 기존과 동일한 집계, 새 CSV 세 개 생성
- 관측: **통과 3/3, 실패 0**. [응답 유실](ledgers/20261013-suite-01-response-lost.csv) 2/2/2/1, [저장 후 중단](ledgers/20261013-suite-01-storage-before-db-stop.csv) 0/0/0/0·후보 1, [동시 전송](ledgers/20261013-suite-01-concurrent-retry.csv) 2/2/2/1.
- 정리: 각 메서드의 `@AfterEach` 실행. 별도 전체 Gradle 콘솔 원본은 보존하지 않았다.
- 해석: 이 실행에서 테스트 간 상태 간섭은 관측되지 않았다.
