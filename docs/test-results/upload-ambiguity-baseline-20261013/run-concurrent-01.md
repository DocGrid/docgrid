# 실행 로그: 동일 HTTP 요청 동시 전송

- 실행 ID: `20261013-concurrent-01`
- 시각: CSV 생성 2026-10-13 21:38:36 KST
- 목적: 같은 사용자·파일·메타데이터를 동시에 보냈을 때 논리 업로드와 물리 파일 중복 제거 결과 확인
- 위치·환경: 격리 작업 트리, 로컬 임시 PostgreSQL 17·pgvector 0.8.1, 시험용 저장소 Port
- 명령: `JWT_SECRET="$(openssl rand -hex 32)" UPLOAD_BASELINE_RUN_ID=20261013-concurrent-01 ./backend/gradlew -p backend test --tests '*UploadAmbiguityBaselineIntegrationTest.concurrentSameRequestCreatesTwoLogicalUploads' --offline --console=plain`
- 사전 성공 기준: HTTP 201 2건, 문서·버전·Job·FileObject 2/2/2/1, 저장 후보 2건 중 미사용 1건 삭제
- 관측: **통과 1/1**. 두 요청 모두 201, 2/2/2/1, 저장 후보 2·삭제 1. [요청별 CSV](ledgers/20261013-concurrent-01-concurrent-retry.csv)
- 정리: 시험의 `@AfterEach`가 생성된 DB 행을 삭제했다. 저장소 후보는 mock에서 추적했다.
- 해석: FileObject 경쟁은 하나로 수렴했지만 문서와 Job은 각각 생성됐다. 실제 다중 앱/GCS 동시성 결과는 아니다.
