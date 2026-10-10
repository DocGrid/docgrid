# HA probe 멱등 재전송 로컬 검증 종합

기준: 2026-10-10 KST. 최초 실행은 `develop`의 `db4c220` 기준 미커밋 변경이었고, 마지막 재검증은 `origin/develop`의 `105d6d6`을 병합한 `9b92eca`에서 수행했다. GCP 앱 배포·VM 장애는 실행하지 않았다. 이 문서는 각 목적·반복 실행의 별도 기록을 연결하며 원본 로그를 합치지 않는다.

| 목적 | 최종 관측 | 상세 기록 |
| --- | --- | --- |
| 신규·기존 Java 계약 | 19건 통과, 실패·건너뜀 0 | [최종 계약 검증](java-contract.md) |
| Python 재전송·원장 | 27건 통과, 실패 0 | [최종 재전송 검증](python-replay.md) |
| PR 전 Java 재검증 | 19건 통과, 실패·건너뜀 0 | [계약 재검증](java-contract-r02.md) |
| PR 전 Python 재검증 | 27건 통과, 실패 0 | [원장 재검증](python-replay-r02.md) |
| 실제 PostgreSQL 제약·경합 | V45 적용 성공, 동시 INSERT 영향 행 합계 1, 시험 후 잔여 행 0, JUnit 1건 통과 | [실DB 경합 검증](postgres-concurrency.md) |
| 같은 트랜잭션 재조회 | V45 적용 성공, `READ COMMITTED` 충돌 후 기존 payload 조회, JUnit 2건 통과·시험 후 잔여 0행 | [추가 실DB 검증](postgres-same-transaction-r03.md) |
| 미커밋 승자 대기 후 재조회 | 잠금 대기 관측 후 승자 커밋, 패자 INSERT 0행·같은 트랜잭션 SELECT 성공, JUnit 3건 통과·시험 후 잔여 0행 | [경합 경계 검증](postgres-waiting-winner-r04.md) |
| 최신 develop 병합 후 Java 계약 | 19건 통과·실패·건너뜀 0 | [병합 후 Java](java-after-develop-r03.md) |
| 최신 develop 병합 후 Python 원장 | 27건 통과·실패 0 | [병합 후 Python](python-after-develop-r03.md) |
| 최신 develop 병합 후 PostgreSQL | V45 적용, 잠금 경합 포함 JUnit 3건 통과·잔여 0행 | [병합 후 PostgreSQL](postgres-after-develop-r05.md) |
| 전체 백엔드 기본 테스트 | 로컬 서비스 DB 연결 실패로 중단, **전체 통과 판정 없음** | [미완료 기록](full-suite-blocked.md) |

반복·차단 실행: [Gradle 캐시 차단](java-first-attempt-blocked.md), [Java 예비 통과](java-preliminary.md), [Python 23건 예비 통과](python-preliminary-23.md), [Python 25건 예비 통과](python-preliminary-25.md), [루프백 바인딩 차단](python-loopback-blocked.md), [강화 PostgreSQL 시험 환경 차단](postgres-enhanced-attempt-blocked.md). 이 실패들은 최종 재실행으로 사라진 기록이 아니라 각 실행의 사실로 유지한다.

해석: 새 멱등 시험 경로의 로컬 계약과 PostgreSQL 핵심 제약은 확인했다. GCP의 두 앱 배포, LB 경유 k6, primary VM 상실, 재전송 전후 실DB 대조, 복구 가드는 아직 별도 안전 관문과 실제 실행이 필요하다.
