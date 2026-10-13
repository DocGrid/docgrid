# 기존 업로드 회귀 시험 실행 기록

- 실행 ID: `upload-idempotency-regression-20261013-01`
- 실행 종료: 2026-10-13 22:09:04 KST (종료 로그 기준)
- 목적·성공 기준: 키 없는 기존 업로드와 결과 불명 기준선 테스트 2개 클래스 전체 통과
- 위치·방법: 로컬 PostgreSQL 17 + pgvector, 시험용 파일 저장소 Port. Gradle `test --tests '*DocumentUploadIntegrationTest' --tests '*UploadAmbiguityBaselineIntegrationTest' --quiet`
- 관측: 기존 업로드 4건, 결과 불명 기준선 3건 통과. 합계 7건, 건너뜀·실패·오류 각 0건. Gradle 종료 코드 0.
- 판정: **통과**. 결과 불명 기준선의 중단 지점은 이전과 같이 결정적 모의이며 실제 프로세스 강제 종료가 아니다.
- 정리: 테스트가 생성한 DB 자료를 삭제했고 시험 DB 컨테이너는 후속 실행을 위해 유지했다.
