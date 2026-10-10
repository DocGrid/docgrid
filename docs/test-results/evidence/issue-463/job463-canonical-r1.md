# job463-canonical-r1 — OpenSQL 인덱싱 Job 읽기 전용 집계

| 항목 | 값 |
| --- | --- |
| 목적 | Worker 활성화 전에 기존 큐와 만료 lease 규모 확인 |
| 실행 위치 | 로컬 보호된 DB 인증 → 만료형 IAP 터널 → GCP 앱 A → OpenProxy → OpenSQL standby |
| 실행 시각 | 2026-10-10 04:30:48–04:30:51 KST |
| 성공 기준 | 읽기 전용 검증, 노드 역할 확인, 상태·claim·문서 상태 집계 |
| 변경 | 없음. `default_transaction_read_only=on` |

| 명령·SQL 요지 | 결과 요약 | 해석 |
| --- | --- | --- |
| `SHOW transaction_read_only` | `on` | 쓰기 비허용 세션에서 조회 |
| `SELECT pg_is_in_recovery(), status, count(*), min(created_at)::date, max(created_at)::date FROM embedding_jobs …` | standby; FAILED 21(10/02–10/06), INDEXED 51(10/03–10/06), PENDING 6(10/09), PROCESSING 2(10/06) | 상태별 현재 스냅샷. 날짜는 KST 변환을 별도 확인하지 않은 DB 세션 날짜 |
| `status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=now())` | 6건 | Worker를 켜면 claim 후보 |
| `status='PROCESSING' AND lock_expires_at<=now()` | 2건 | lease 복구 후보 |
| Job → Version → Document join 후 상태·삭제 여부만 집계 | PENDING 6건: 문서 UPLOADED·미삭제 / PROCESSING 2건: 문서 DELETED·삭제 표시 | 오래된 처리 중 Job까지 활성화 영향 범위에 포함 |

앞선 탐색 실행에서 PENDING 5건을 봤으나, 후속 실행과 같은 SQL 역할 확인 3회에서는 6건이었다. 탐색 실행은 시각·노드 역할이 빠져 있어 변화의 원인으로 사용하지 않는다. Job ID, 제목, 사용자, DB 주소·비밀은 기록하지 않았다. 최종 활성화 전에는 primary 최신 상태를 재조회해야 한다.
