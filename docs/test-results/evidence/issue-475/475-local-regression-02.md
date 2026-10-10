# `475-local-regression-02` — 로컬 CORS·RAG 회귀 재실행

- 목적: 병합 소스의 공개 Origin 허용 및 교차 노드 알림 회귀 확인
- 위치·시각: 로컬 격리 worktree, 2026-10-10 약 06:04 KST. 명령 시작 초 단위 시각은 별도 수집하지 못했다.
- 소스: `04c9b0c`
- 명령: `./backend/gradlew -p backend test --tests …CorsConfigTest --tests …RagWebSocketControllerTest --tests …RagAnswerCrossNodeSignalTest`
- 성공 기준: 세 클래스 모두 실패·오류 0건
- 원본 요약: Gradle `BUILD SUCCESSFUL in 4s`; XML 기준 CORS 1건, 교차 신호 4건, 컨트롤러 1건 = **6/6 통과, 실패 0, 오류 0**.
- 이어서 `./backend/gradlew -p backend bootJar`가 성공했고 JAR SHA-256은 `eafc09e71fd60008baf42ca3cfbd10c5af7c6e986688ec7c37d21b2ab655e7a8`이다.
- 해석: 이 범위의 로컬 회귀와 빌드가 통과했다. 전체 Gradle 테스트는 이번 실행에서 돌리지 않았다.
