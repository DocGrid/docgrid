# Barman 외부 보존 단위 시험 · UNIT-R4

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 전송 실패 시 이전 성공 시각 보존과 최근 실패 기록까지 검증 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 scripts/opensql/test_barman_offsite_sync.py -v` |
| 실행 ID | `BARMAN-OFFSITE-UNIT-20261017-R4` |
| 성공 기준 | 6개 시험 전부 통과, 마지막 성공 시각이 실패 실행에 의해 갱신되지 않음 |

## 관측 결과

2026-10-17 00:42 KST 실행 결과 6/6 통과, 실패 0건, 0.007초였다. 새 시험은 WAL 전송 실패 후에도 `last_success_utc`가 이전 값으로 남고 `last_error`가 기록됨을 확인했다. 로컬 모의 명령 시험이므로 실제 GCS 장애 재현과 구분한다.
