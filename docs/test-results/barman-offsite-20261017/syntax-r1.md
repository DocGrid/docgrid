# Barman 외부 보존 구문 점검 · SYNTAX-R1

## 실행 전 의도

| 항목 | 값 |
| --- | --- |
| 목적 | 새 Python 파일의 구문 오류 확인 |
| 실행 위치 | 격리된 로컬 작업 트리 |
| 명령 | `python3 -m py_compile scripts/opensql/barman_offsite_sync.py` |
| 실행 ID | `BARMAN-OFFSITE-SYNTAX-20261017-R1` |
| 성공 기준 | Python 바이트코드 컴파일 성공 |

## 관측 결과

2026-10-17 00:35 KST 실행은 **실패**했다. Python이 구문 분석 전에 기본 캐시 경로를 만들려다 샌드박스 권한 오류를 냈다. 따라서 이 실행은 구문 검증의 증거가 아니다. 캐시 경로를 허용된 임시 디렉터리로 바꿔 다시 확인한다.
