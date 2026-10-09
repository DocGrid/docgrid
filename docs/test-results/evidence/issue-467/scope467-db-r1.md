# 문서 버전 필터 SQL 확인 — scope467-db-r1

| 항목 | 기록 |
| --- | --- |
| 시각 | 2026-10-10 04:49~04:53 KST |
| 장소 | 로컬 임시 SSH 터널 → GCP 앱 A → OpenProxy → OpenSQL primary |
| 대상 | 현재 클러스터의 기존 Job을 변경하지 않는 SQL |
| 목적 | 새 native SQL의 실제 스키마 호환성과 문서 버전 조건 확인 |
| 방법 | 명시적 read-write 트랜잭션에서 `pg_is_in_recovery()`와 필터 Claim·만료 후보 SELECT 실행 후 `ROLLBACK` |
| 판정 | primary 도착, 선택 행의 문서 버전 일치, SQL 오류 0건, 변경 커밋 0건 |

| 단계 | 결과 | 해석 |
| --- | --- | --- |
| 로컬 격리 DB 준비 | Docker API 500으로 실패 | 컨테이너가 생성되지 않았다. 로컬 Repository 통합 테스트는 미실행이다. |
| 첫 SSH 터널 | 연결 거부 | 로컬 설정의 오래된 프록시 주소를 사용했다. DB 질의는 실행되지 않았다. |
| 앱 A 적용 설정으로 임시 터널 재연결 | 성공 | 현재 앱의 프록시 경로를 사용했다. 주소와 비밀은 출력·문서화하지 않았다. |
| 필터 Claim SELECT | `role=primary`, `filtered_claim_match=True` | 실제 OpenSQL 스키마에서 SQL이 실행됐고 반환된 후보가 지정 문서 버전에 속했다. |
| 필터 만료 Lease SELECT | 오류 0건, 후보 0건 | SQL 구문은 실행됐다. 만료 후보가 없어서 반환 행의 범위 비교는 하지 못했다. |
| 정리 | `ROLLBACK`, 연결 종료, SSH 터널 종료 | 기존 Job 상태를 변경하지 않았다. |

이 결과는 읽기 전용 SQL 호환성 확인이다. `FOR UPDATE SKIP LOCKED`의 두 Worker 동시성이나 실제 Worker Scheduler의 GCP 실행을 증명하지 않는다.
