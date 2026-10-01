# unit-01 — 초기 일괄 판정 단위 시험

| 항목 | 기록 |
| --- | --- |
| 시각·위치 | 2026-10-02 KST, 로컬 JVM. 정확한 분·초는 별도 수집하지 못함 |
| 목적·성공 조건 | 사용자 중복 제거, primary/standby 차단, 내부 판정 누락 차단, 재처리 응답 유지 |
| 실행 | `./backend/gradlew -p backend test --tests '...PrimaryRoleQueryServiceTest' --tests '...StompDashboardOutboundAuthorizationInterceptorTest' --tests '...DashboardWebSocketControllerTest' --tests '...EmbeddingJobRetryServiceTest' --rerun-tasks --quiet` |
| 결과 | **21/21 통과**(6+6+3+6), 실패 0·건너뜀 0 |
| 해석·한계 | 초기 코드의 단위 계약 통과. 이후 chunking·dirty 재시도 테스트를 추가해 최종 48건 실행에 포함 |
