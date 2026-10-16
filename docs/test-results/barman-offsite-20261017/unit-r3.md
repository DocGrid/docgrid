# Barman 외부 보존 단위 시험 · UNIT-R3

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 기존 GCS 객체를 덮어쓰지 않는 변경과 완료 표시 재시도 경계 검증 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 scripts/opensql/test_barman_offsite_sync.py -v` |
| 실행 ID | `BARMAN-OFFSITE-UNIT-20261017-R3` |
| 성공 기준 | 5개 시험 전부 통과, 실패 후 재시도에서 아카이브 재전송 0회 |

## 관측 결과

2026-10-17 00:39 KST 실행 결과 5/5 통과, 실패 0건, 0.006초였다. 완료 표시 업로드 실패 뒤 아카이브는 보존됐고 재시도 시 기존 원격 객체를 다시 올리지 않았다(재전송 0회). 모의 명령을 사용한 로컬 시험이며 실제 GCS 전송은 다음 실행에서 검증한다.
