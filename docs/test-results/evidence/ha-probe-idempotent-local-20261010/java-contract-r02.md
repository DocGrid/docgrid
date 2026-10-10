# HA probe 멱등 HTTP 계약 재검증

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-idem-java-20261010-02`, 2026-10-10 11:35 KST |
| 위치·리비전 | 로컬 작업 브랜치, `db4c220` 기준 미커밋 변경 |
| 목적·명령 | 새 멱등 API와 기존 probe의 선택 Java 회귀; `./backend/gradlew -p backend test --tests '*HaProbeIdempotentWrite*' --tests '*HaProbeWrite*' --no-daemon --console=plain` |
| 성공 기준 | 모든 선택 시험 통과, 실패·건너뜀 0 |
| 관측 | JUnit XML 합계 **19건**, 실패 **0건**, 건너뜀 **0건**; Gradle 종료 코드 **0** |
| 범위 | MVC·보안·서비스 단위 계약만 해당. 실제 PostgreSQL 검증은 별도 실행 기록 참조 |

JUnit XML 원본은 로컬 컴퓨터 이름을 포함할 수 있어 저장소에 넣지 않았다. 전체 백엔드 테스트는 앞선 로컬 의존성 차단 기록을 통과로 바꾸지 않는다.
