# Barman import의 실제 식별 조회 경로 확인 · IMPORT-DIAGNOSE-R5

## 실행 전 계획 (2026-10-17 02:44 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-IMPORT-DIAGNOSE-R5` |
| 목적 | DB systemid가 일치하는데도 import가 거절되는 조건을 Barman 공개 코드와 안전한 오류 분류로 특정 |
| 위치·방법 | 백업 VM에 설치된 Barman 공개 Python 코드의 import 진입점·식별 계산식만 읽기 전용 검사 |
| 합격 기준 | import가 참조하는 로컬 식별 값의 생성 경로 확인 |
| 보호 | 런타임 설정값·원본 오류·내부 식별자 출력 없음 |

## 관측

2026-10-17 **02:43:44~02:43:52 KST**에 설치된 Barman의 공개 `Server.import_backup` 경로를 확인했다. 이 명령은 tar 내부 identity를 읽기 전에 **대상 카탈로그의 기존 `identity.json`을 먼저 요구**하고, 없으면 import를 거절한다. 따라서 빈 격리 카탈로그가 원인이며 DB 연결 문자열만 고쳐도 해결되지 않았다. 임시 OS Login 키 제거 성공. **원인 특정**.
