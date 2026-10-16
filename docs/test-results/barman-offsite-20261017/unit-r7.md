# Barman 외부 보존 단위 시험 · UNIT-R7

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 백업 ID가 다른 아카이브를 현재 백업의 완료 사본으로 받아들이지 않는지 검증 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 scripts/opensql/test_barman_offsite_sync.py -v` |
| 실행 ID | `BARMAN-OFFSITE-UNIT-20261017-R7` |
| 성공 기준 | 8개 시험 전부 통과, 잘못된 백업 ID의 export에서 `export_identity` 실패 |

## 관측 결과

2026-10-17 01:00 KST에 실행했다. 8개 중 8개가 통과했고 0개가 실패했다. 다른 백업 ID의 아카이브는 `export_identity` 오류로 거부됐다. 출력에는 건강 점검 단위 시험의 예상 비정상 JSON이 포함됐으며 실제 서비스 상태를 뜻하지 않는다.
