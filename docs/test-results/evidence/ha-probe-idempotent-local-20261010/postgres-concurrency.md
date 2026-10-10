# HA probe 멱등 PostgreSQL 경합 검증

| 항목 | 기록 |
| --- | --- |
| 실행 ID | `local-ha-idem-pg-20261010-01` |
| 기록 시각 | 2026-10-10 04:48 KST |
| 위치·리비전 | 로컬 일회성 PostgreSQL 16, `develop` 기준 `db4c220` + 미커밋 변경 |
| 목적 | V45 DDL과 두 연결의 동일 ID 동시 INSERT, 재전송 시 추가 행 0건, 시험 행 정리 확인 |
| 방법 | PostgreSQL 16 일회성 컨테이너에 V45 적용 → `HaProbeIdempotentPostgresIntegrationTest` 실행 → 행 수 조회 → 컨테이너 중지 |
| 성공 기준 | 동시 INSERT의 영향 행 합계 1, 동일 ID 재INSERT 0, 저장 행 1, 종료 후 시험 행 0 |
| 관측 | V45 `CREATE TABLE` 성공; **통합 테스트 1건 통과·건너뜀 0·실패 0**; 종료 후 시험 행 0 |

컨테이너는 로컬 루프백에만 게시했고 비밀번호 대신 일회성 테스트 인스턴스의 trust 인증을 사용했다. 외부 GCP DB에는 접속하지 않았다. 시험 전 Docker 데몬은 중지돼 있었고, 시험을 위해 잠시 시작했다가 컨테이너 자동 제거 및 실행 컨테이너 0개 확인 후 다시 종료했다.

이 검증은 PostgreSQL 제약과 SQL 경합을 확인한다. 앱 A/B의 실제 분산 동시 HTTP 요청이나 primary VM 상실에서의 RTO·RPO를 측정한 결과는 아니다. 같은 트랜잭션의 다음 SELECT까지 확인하는 강화 시험은 별도 재시도를 계획했지만 Docker API 준비 실패로 실행하지 못했다. 실행되지 않은 강화 수정은 되돌려 이 문서에서 통과한 시험 코드와 일치시켰다.
