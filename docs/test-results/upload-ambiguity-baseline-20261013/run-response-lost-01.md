# 실행 로그: 응답 유실 첫 시도

- 실행 ID: `20261013-response-lost-01`
- 시각: 2026-10-13 21:37 KST, 분 단위. 상세 시작·종료 시각은 보존되지 않았다.
- 목적: DB 커밋 후 첫 HTTP 응답을 버리고 같은 입력을 재전송했을 때의 기준선 측정
- 위치·환경: 격리 작업 트리, 로컬 임시 PostgreSQL 17·pgvector 0.8.1, Spring `test` 프로필
- 명령: `UPLOAD_BASELINE_RUN_ID=20261013-response-lost-01 ./backend/gradlew -p backend test --tests '*UploadAmbiguityBaselineIntegrationTest.responseLostAfterCommit_thenRetryCreatesSecondDocument' --offline --console=plain`
- 사전 성공 기준: 첫 응답을 폐기해도 첫 DB 결과 1건, 재전송 후 문서·버전·Job 2건과 FileObject 1건
- 관측: **실패 1/1**. Spring 컨텍스트 생성 중 `JWT_SECRET` 설정이 없어 시험 메서드에 진입하지 못했다. 요청·DB 측정 표본 0건, CSV 원장 없음.
- 재시도: 임시 시험 값을 프로세스 메모리에서 생성해 별도 실행 ID `20261013-response-lost-02`로 재실행했다.
- 해석: 제품 동작의 실패로 분류하지 않는다. 이 실행의 상세 Gradle 출력은 별도 파일로 보존되지 않았다.
