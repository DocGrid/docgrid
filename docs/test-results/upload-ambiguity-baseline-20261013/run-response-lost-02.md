# 실행 로그: 응답 유실 재전송

- 실행 ID: `20261013-response-lost-02`
- 시각: CSV 생성 2026-10-13 21:37:48 KST
- 목적: DB 커밋 후 응답 폐기와 동일 입력 재전송의 현재 결과 확인
- 위치·환경: 격리 작업 트리, 로컬 임시 PostgreSQL 17·pgvector 0.8.1, Spring `test` 프로필
- 명령: `JWT_SECRET="$(openssl rand -hex 32)" UPLOAD_BASELINE_RUN_ID=20261013-response-lost-02 ./backend/gradlew -p backend test --tests '*UploadAmbiguityBaselineIntegrationTest.responseLostAfterCommit_thenRetryCreatesSecondDocument' --offline --console=plain`
- 사전 성공 기준: 첫 커밋 후 1/1/1/1, 재전송 후 문서·버전·Job·FileObject 2/2/2/1
- 관측: **통과 1/1**. A1 서버 201을 클라이언트에서 폐기했을 때 1/1/1/1; A2가 201을 받은 뒤 2/2/2/1. 저장소 후보 생성 1건, 삭제 0건. [요청별 CSV](ledgers/20261013-response-lost-02-response-lost.csv)
- 정리: 시험의 `@AfterEach`가 생성된 문서·버전·Outbox·Job·FileObject를 삭제했다.
- 해석: 현재 API는 같은 HTTP 요청을 식별하지 못한다. 실제 네트워크 유실이나 GCS 실행 결과는 아니다.
