# 실행 로그: 저장소 기록 직후 중단 경계

- 실행 ID: `20261013-storage-stop-01`
- 시각: CSV 생성 2026-10-13 21:38:15 KST
- 목적: 저장소 후보가 생성된 직후 DB 트랜잭션에 들어가기 전에 중단됐을 때의 참조·정리 상태 확인
- 위치·환경: 격리 작업 트리, 로컬 임시 PostgreSQL 17·pgvector 0.8.1, 시험용 GCS 저장소 Port
- 명령: `JWT_SECRET="$(openssl rand -hex 32)" UPLOAD_BASELINE_RUN_ID=20261013-storage-stop-01 ./backend/gradlew -p backend test --tests '*UploadAmbiguityBaselineIntegrationTest.storageCompleted_thenProcessStopBoundaryLeavesOrphan' --offline --console=plain`
- 사전 성공 기준: 저장 후보 1건, 보상 삭제 0건, DB 문서·버전·Job·FileObject 0건
- 관측: **통과 1/1**. 후보 1건·삭제 0건·DB 0/0/0/0. [요청별 CSV](ledgers/20261013-storage-stop-01-storage-before-db-stop.csv)
- 정리: 저장소는 시험용 mock이라 실제 GCS 객체는 없다. DB에 시험 문서가 없음을 확인했다.
- 해석: `Error` 주입으로 보상 삭제가 실행되지 않는 경계를 모의했다. JVM 강제 종료와 실제 GCS 저장·삭제를 관측한 결과는 아니다.
