# 검증된 export 식별 파일로 격리 import · IMPORT-R3

## 실행 전 계획 (2026-10-17 02:44 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-IMPORT-R3` |
| 목적 | 새 Barman 카탈로그의 필수 서버 식별 파일을 준비한 뒤 외부 export import·verify 완료 |
| 위치·방법 | 별도 stage에 이미 다운로드한 외부 tar의 파일명 SHA-256 접두사·CRC32C·크기를 재검사. tar의 `identity.json`을 현재 시험 DB의 systemid와 메모리에서 대조하고, 격리 카탈로그에만 0600으로 쓴 뒤 import·verify |
| 합격 기준 | 식별 값 일치, 대상 identity 파일 전용 경로, 격리 백업 1건 `DONE`, `verify-backup` 성공, 기존 카탈로그 1건 유지 |
| 보호 | tar의 임의 경로를 추출하지 않고 `identity.json` 내용만 안전하게 파싱. 실제 identity 값·파일 경로·DB 접속 정보는 기록하지 않음. 원본 카탈로그와 DB는 무변경 |

## 관측

2026-10-17 **02:44:54~02:45:16 KST**에 외부 tar의 크기·CRC32C·파일명 SHA-256 접두사 **3/3 일치**를 다시 확인했다. tar의 identity와 현재 시험 DB의 systemid가 일치해 격리 카탈로그에만 0600 식별 파일을 썼다. `import-backup`은 이번에 성공했고 격리 카탈로그에 백업 **1건**이 등록됐다. 다만 `verify-backup`은 종료 코드 **1**, 외부 실행 파일 누락 유형의 오류로 실패했다. 임시 OS Login 키 제거 성공. **import 통과, verify 미통과**. 기존 카탈로그와 원본 DB는 변경하지 않았다.
