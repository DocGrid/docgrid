# 실행 로그: 시험 사용자 선택 변경 후 연속 실행

- 실행 ID: `20261013-suite-02`
- 시각: CSV 생성 2026-10-13 21:41:12 KST
- 목적: 테스트 코드에서 개인 이메일 리터럴을 제거한 뒤 세 시나리오가 그대로 재현되는지 확인
- 위치·환경: 격리 작업 트리, 로컬 임시 PostgreSQL 17·pgvector 0.8.1
- 명령: `JWT_SECRET="$(openssl rand -hex 32)" UPLOAD_BASELINE_RUN_ID=20261013-suite-02 ./backend/gradlew -p backend test --tests '*UploadAmbiguityBaselineIntegrationTest' --offline --console=plain`
- 사전 성공 기준: 세 메서드 모두 통과, 각 시나리오 집계 유지, 시험 문서 잔류 0건
- 관측: **통과 3/3, 실패 0**. [응답 유실](ledgers/20261013-suite-02-response-lost.csv) 2/2/2/1, [저장 후 중단](ledgers/20261013-suite-02-storage-before-db-stop.csv) 0/0/0/0·후보 1, [동시 전송](ledgers/20261013-suite-02-concurrent-retry.csv) 2/2/2/1. 종료 후 시험 제목 접두어로 DB 조회한 문서 **0건**.
- 정리: 임시 PostgreSQL 컨테이너를 종료·제거했다. 저장소는 mock이므로 삭제할 실제 GCS 객체는 없다.
- 해석: 로컬 기준선이 재현됐다. 클라우드 장애나 실제 프로세스 종료 결과는 아니다.
