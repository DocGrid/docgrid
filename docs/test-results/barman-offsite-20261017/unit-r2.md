# Barman 외부 보존 단위 시험 · UNIT-R2

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | R1 시험 코드의 명령 인자 위치 수정 후 동일한 다섯 경계를 재검증 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 scripts/opensql/test_barman_offsite_sync.py -v` |
| 실행 ID | `BARMAN-OFFSITE-UNIT-20261017-R2` |
| 성공 기준 | 5개 시험 전부 통과, 비공개 인프라 식별자 출력 0건 |

## 관측 결과

2026-10-17 00:37 KST 실행 결과 5/5 통과, 실패 0건, 0.006초였다. 미완료 아카이브 거부, 검증 뒤 완료 표시, 표시 업로드 실패 후 재시도, `.partial` WAL 제외, 오래된 마지막 성공 시각 감지를 확인했다. 모의 명령을 사용한 로컬 시험이며 GCS 실제 전송 결과는 아니다.
