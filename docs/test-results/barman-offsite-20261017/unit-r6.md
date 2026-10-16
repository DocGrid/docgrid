# Barman 외부 보존 단위 시험 · UNIT-R6

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | `gsutil rsync -x`의 경로 시작 매칭에 맞춰 완성 WAL·timeline만 허용하는지 검증 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 scripts/opensql/test_barman_offsite_sync.py -v` |
| 실행 ID | `BARMAN-OFFSITE-UNIT-20261017-R6` |
| 성공 기준 | 7개 시험 통과, 완성 WAL·history는 제외식 미일치, `.partial`·`xlog.db`는 제외식 일치 |

## 관측 결과

2026-10-17 00:53 KST 실행 결과 7/7 통과, 실패 0건, 0.007초였다. 상대 경로를 사용한 정규식 판정에서 완성 세그먼트와 timeline history는 동기화 대상으로 남고 `.partial` 및 `xlog.db`는 제외됐다. 실제 GCS 파일 목록은 R3에서 별도로 대조한다.
