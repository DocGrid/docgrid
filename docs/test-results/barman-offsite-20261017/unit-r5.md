# Barman 외부 보존 단위 시험 · UNIT-R5

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 실제 Barman `Status                 : DONE` 공백 형식을 완료 백업으로 인식하고 기존 경계의 회귀가 없는지 확인 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 scripts/opensql/test_barman_offsite_sync.py -v` |
| 실행 ID | `BARMAN-OFFSITE-UNIT-20261017-R5` |
| 성공 기준 | 7개 시험 전부 통과, 실제 형식의 완료 백업 ID 1건 반환 |

## 관측 결과

2026-10-17 00:49 KST 실행 결과 7/7 통과, 실패 0건, 0.007초였다. 실제 Barman 상태 줄 형식을 완료 백업 1건으로 판정했고 기존 실패·재시도 경계도 통과했다. 로컬 모의 명령 결과로, GCS 실제 전송은 R2에서 별도 확인한다.
