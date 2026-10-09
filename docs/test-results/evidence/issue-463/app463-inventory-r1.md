# app463-inventory-r1 — GCP 앱 A/B 실행 상태

| 항목 | 값 |
| --- | --- |
| 목적 | A/B 실행 바이트와 Worker·스토리지·임베딩 적용 설정을 비밀 없이 비교 |
| 실행 위치 | GCP 앱 A, 앱 B 게스트 내부 (만료형 IAP SSH) |
| 실행 시각 | A: 2026-10-10 04:31:12–04:31:13 KST / B: 04:31:09–04:31:10 KST |
| 성공 기준 | 서비스 2/2 active, JAR SHA-256 동일, Worker 적용값·URL 설정 여부 확인 |
| 정리 | 읽기 전용. 키는 뒤의 연결 시험 이후 회수 예정 |

| 실행 명령·검사 위치 | A 결과 | B 결과 | 해석 |
| --- | --- | --- | --- |
| `systemctl is-active docgrid` / 각 앱 VM | `active` | `active` | 서비스 2/2 실행 |
| `find /opt/docgrid -name '*.jar' -exec sha256sum …` / 각 앱 VM | `a321b1e6…edd8ff5de89` | `a321b1e6…edd8ff5de89` | JAR 바이트 일치. 커밋은 미확정 |
| 보호된 환경파일에서 `INDEXING_WORKER_ENABLED`, `STORAGE_TYPE`, `SYNC_DISPATCHER_ENABLED`만 제한 출력 / 각 앱 VM | `false`, `gcs`, `false` | `false`, `gcs`, `false` | Worker·Sync Dispatcher 비활성, GCS 설정 |
| 환경파일의 `EMBEDDING_SERVER_URL` 키 존재 여부만 검사 / 각 앱 VM | 미설정 | 미설정 | CPU VM 연결 미적용. 기본 localhost URL이 사용될 수 있음 |

민감 값, 내부 URL, 호스트·프로젝트 ID는 출력하지 않았다. 서비스 파일 전체나 환경파일 원문도 수집하지 않았다.
