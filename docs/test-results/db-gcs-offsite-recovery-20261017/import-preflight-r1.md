# 격리 Barman import 사전 점검 · IMPORT-PREFLIGHT-R1

## 실행 전 계획 (2026-10-17 02:36 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-IMPORT-PREFLIGHT-R1` |
| 목적 | 외부 export와 WAL을 별도 경로에 가져올 공간·도구·권한을 확인하고 기존 Barman 카탈로그를 건드리지 않을 경계 확정 |
| 위치·방법 | 백업 VM의 Barman 버전, 별도 stage 여유 공간, 별도 설정 지원 여부를 읽기 전용으로 확인 |
| 합격 기준 | 1GiB 이상 여유 공간, Barman CLI와 `import-backup` 제공, 격리 stage 미존재 또는 비어 있음 |
| 제한 | 이 단계는 다운로드·import·원본 DB 변경 없이 조회만 수행 |

## 관측

2026-10-17 **02:36:18~02:36:28 KST**에 조회했다. 백업 VM의 여유 공간은 **27,757,457,408바이트**, Barman CLI와 별도 설정 옵션·`import-backup` 명령을 확인했다. 이번 시험의 격리 stage는 아직 존재하지 않았다. 임시 OS Login 키 제거 성공. **통과**.
