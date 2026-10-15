# RAG 트랜잭션 경계 단위 시험 — run-tx-boundary-unit-01

| 항목 | 기록 |
| --- | --- |
| 목적 | 모델 호출과 완료 저장을 분리한 뒤 기존 RAG 성공·실패·인용 동작의 회귀 확인 |
| 실행 시각 | 2026-10-16 05:24 KST |
| 위치 | 로컬 작업 브랜치, JVM 단위 시험 |
| 명령 | `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.rag.service.RagFacadeTest' --console=plain` |
| 성공 기준 | 20개 시험 실패 0개 |
| 결과 | 20개 통과, 실패 0개, 건너뜀 0개. Gradle BUILD SUCCESSFUL (8초) |

앞선 샌드박스 실행은 Gradle 캐시 잠금 파일을 만들 권한이 없어 시험 시작 전에 종료됐다. 승인된 실행 환경에서 다시 수행해 위 결과를 얻었다. 이 단위 시험은 실제 DB 커넥션이 모델 호출 동안 반환되는지 직접 측정하지 않는다. 그 경계는 후속 통합·A/B 시험에서 확인한다.
