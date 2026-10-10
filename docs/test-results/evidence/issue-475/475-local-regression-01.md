# `475-local-regression-01` — 로컬 회귀 시험 첫 시도

- 목적: 병합된 CORS와 RAG 알림 코드의 3개 테스트 클래스 실행
- 위치·시각: 로컬 격리 worktree, 2026-10-10 약 06:04 KST. 명령 시작 초 단위 시각은 별도 수집하지 못했다.
- 소스: `04c9b0c`
- 명령: `./backend/gradlew -p backend test --tests …CorsConfigTest --tests …RagWebSocketControllerTest --tests …RagAnswerCrossNodeSignalTest`
- 성공 기준: 3개 클래스 모든 테스트 통과
- 결과: Gradle 캐시 잠금 파일 접근이 샌드박스에서 `Operation not permitted`로 실패했다. 테스트 실행 0건.
- 해석: 코드 실패가 아니라 명령 실행 권한 실패다. 허용된 환경으로 같은 명령을 다시 실행했다.
