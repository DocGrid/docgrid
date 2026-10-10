# 최신 develop 병합 후 HA probe PostgreSQL 경합

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-idem-pg-20261010-05`, 2026-10-10 11:37 KST |
| 환경·리비전 | 로컬 루프백 전용 일회성 PostgreSQL 16, `origin/develop`의 `105d6d6`을 병합한 `9b92eca` |
| 목적·절차 | 최신 코드의 V45 적용 → `HaProbeIdempotentPostgresIntegrationTest` 실행 → 잔여 행 확인 → 컨테이너 자동 제거 |
| 성공 기준·관측 | V45 적용 성공, 기존 동시 INSERT·동일 트랜잭션 재조회·실제 잠금 대기 경합의 JUnit **3건 통과·실패 0·건너뜀 0**, 잔여 **0행**, 종료 코드 **0** |
| 한계 | 로컬 SQL 계약 검증이며 앱 A/B의 실제 HTTP 동시 호출이나 GCP 장애 결과는 아님 |

명령: `HA_PROBE_TEST_JDBC_URL`을 일회성 루프백 포트로 설정해 `./backend/gradlew -p backend test --tests '*HaProbeIdempotentPostgresIntegrationTest' --console=plain` 실행. 연결 정보·로컬 컴퓨터 이름이 포함된 원본 JUnit XML은 저장소에 넣지 않았다.
