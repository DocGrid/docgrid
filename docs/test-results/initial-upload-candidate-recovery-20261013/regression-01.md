# 업로드 경로 합동 회귀 시험

- 실행 ID: `upload-candidate-regression-20261013-01`
- 시각: 2026-10-13 22:52:11~22:52:13 KST (JUnit 실행 기록의 UTC 시각을 변환). 종료 로그: 22:52:13 KST.
- 목적: 기존 키 없는 최초 업로드와 새 후보 복구가 함께 동작하는지 확인.
- 위치: 로컬 PostgreSQL 17·pgvector 격리 컨테이너, 모의 저장소 및 모의 GCS SDK. 실제 GCP 접근 없음.
- 명령: `JWT_SECRET=[시험 전용 값 생략] ./backend/gradlew -p backend test --tests '...DocumentUploadFacadeTest' --tests '...DocumentUploadServiceTest' --tests '...GcsStorageServiceTest' --tests '...DocumentUploadIntegrationTest' --tests '...InitialUploadIdempotencyIntegrationTest' --offline --no-daemon` (생략부는 각 클래스의 전체 패키지명).
- 성공 기준: 다섯 클래스 시험 전부 통과, 실패·오류·건너뜀 0건, 종료 코드 0.
- 관측: 기존 업로드 통합 4건, 멱등·복구 통합 11건, Facade 4건, 업로드 서비스 3건, GCS 어댑터 9건. **총 31건 통과**, 실패·오류·건너뜀 각 0건. Gradle `BUILD SUCCESSFUL in 10s`, 종료 코드 0.
- 판정: 통과. 외부 GCS 세대 조건과 Cloud Run 장애는 모의 범위 밖이다.
- 정리: 실행 후 일회성 DB 컨테이너를 중단했고 자동 제거됐다.
