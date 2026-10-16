# Barman 외부 보존 구문 점검 · SYNTAX-R2

## 실행 전 계획

| 항목 | 값 |
| --- | --- |
| 목적 | 캐시 경로를 허용된 임시 디렉터리로 지정해 Python 구문 확인 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-pycache python3 -m py_compile scripts/opensql/barman_offsite_sync.py scripts/opensql/test_barman_offsite_sync.py` |
| 실행 ID | `BARMAN-OFFSITE-SYNTAX-20261017-R2` |
| 성공 기준 | 두 파일 모두 컴파일 성공 |

## 관측 결과

2026-10-17 00:37 KST 실행 결과 두 파일 모두 컴파일에 성공했다. 이 결과는 구문에 한정되며 외부 전송 동작을 입증하지 않는다.
