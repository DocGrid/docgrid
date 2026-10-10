# RAG 교차 노드 Java 회귀 — `rag473-java-r1`

- 목적: Redis 신호의 자기 echo·원격 수신·DB 소유자 확인·비활성 설정과 기존 RAG Worker를 검증한다.
- 위치·시각: 로컬 JDK 17, 2026-10-10 약 05:39 KST.
- 실행: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.rag.controller.RagAnswerCrossNodeSignalTest' --tests 'com.opensource.docgrid.domain.rag.controller.RagWebSocketControllerTest' --tests 'com.opensource.docgrid.domain.rag.service.RagJobWorkerTest' --tests 'com.opensource.docgrid.domain.rag.service.RagJobTimeoutSweeperTest'`
- 완료 기준: 19건 실패·건너뜀 0, 빌드 성공.

| 실행 | 결과 | 해석 |
| --- | --- | --- |
| 첫 명령, 제한된 셸 | Gradle wrapper 캐시 잠금 파일 권한 오류, 테스트 시작 전 종료 | 코드 실패로 세지 않음 |
| 권한을 갖춘 같은 명령 재실행 | Signal 4/4, Controller 1/1, Worker 10/10, Sweeper 4/4 = **19/19 통과** | 로컬 단위 계약 통과. 실제 Redis·A/B 브로커는 별도 실측 |
| `./backend/gradlew -p backend bootJar -x test` | 빌드 성공, JAR SHA-256 `64dae86ab1b683f911900ce99e1e2441d2bff4d1fde6a31575e6fa07275c3f36` | GCP 검증 이미지 식별 |

Gradle 테스트 XML에서 네 suite의 실패·오류·건너뜀은 모두 0으로 재확인했다. 비공개 호스트 경로·계정은 기록하지 않았다.
