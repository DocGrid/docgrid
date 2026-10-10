# HA probe 멱등 HTTP 계약 로컬 검증

| 항목 | 기록 |
| --- | --- |
| 실행 ID | `local-ha-idem-java-20261010-01` |
| 기록 시각 | 2026-10-10 04:50 KST |
| 위치·리비전 | 로컬 격리 작업 공간, `develop` 기준 `db4c220` + 미커밋 변경 |
| 목적 | 기존 중복 탐지 API를 유지하면서 새 API의 201·200·409·400·500, ADMIN 인가, profile/flag 차단, SQL 분기 확인 |
| 명령 | `./backend/gradlew -p backend test --tests '*HaProbeIdempotentWrite*' --tests '*HaProbeWrite*' --no-daemon` |
| 성공 기준 | 선택된 테스트 전부 통과, 실패·건너뜀 0 |
| 관측 | **19건 통과, 실패 0건, 건너뜀 0건**, Gradle 종료 코드 0 |

검증된 것은 단위·MVC 계약이다. 실제 DB 경합은 별도 PostgreSQL 기록에서 확인한다. 앞선 차단·반복 실행과 전체 기본 테스트의 결과는 각각 별도 기록으로 남겼다.
