# Barman 외부 보존 단위 시험 · UNIT-R1

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 미완료 export 거부, 업로드 검증 뒤 완료 표시, 실패 후 재시도, 미완성 WAL 제외, 마지막 성공 시각 점검 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 scripts/opensql/test_barman_offsite_sync.py -v` |
| 실행 ID | `BARMAN-OFFSITE-UNIT-20261017-R1` |
| 성공 기준 | 5개 시험 통과, 비공개 버킷·호스트 식별자 출력 0건 |

## 관측 결과

2026-10-17 00:36 KST에 로컬에서 실행했다. 5개 중 4개 통과, 1개 실패했다. 실패는 WAL 제외 정규식을 확인하는 **시험 코드가 명령 인자 목록 전체에서 문자열을 검색한 오류**였다. 실제 명령의 제외 인자에는 `.partial` 정규식이 있었다. 시험의 인자 위치를 수정하고 별도 R2로 재실행한다. 외부 전송은 수행하지 않았다.
