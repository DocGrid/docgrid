# 아카이브와 DB 식별 값의 비공개 대조 · IMPORT-DIAGNOSE-R4

## 실행 전 계획 (2026-10-17 02:43 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-IMPORT-DIAGNOSE-R4` |
| 목적 | 외부 export의 `systemid`·버전이 현재 DB와 일치하는지, 격리 Barman 설정으로 현재 DB에 접속 가능한지 확인 |
| 위치·방법 | 백업 VM에서 아카이브 identity를 메모리로 읽고 기존·격리 설정의 접속 문자열을 사용한 읽기 전용 DB 조회와 비교. import 오류는 알려진 템플릿 여부만 판정 |
| 합격 기준 | 실제 DB의 systemid·버전과 export의 일치 여부, 격리 연결 성공 여부를 값 노출 없이 확인 |
| 보호 | systemid·버전 원문·DSN·주소·서버명·경로 미출력 |

## 관측

2026-10-17 **02:43:04~02:43:12 KST**에 원본·격리 Barman 설정의 연결 문자열이 같고 두 설정 모두 현재 시험 DB에 읽기 접속된다는 것을 확인했다. 외부 export의 `systemid`는 두 경로에서 조회한 DB 값과 **일치**했다. export 버전 문자열과 `server_version_num`의 표현 형식은 달라 단순 문자열 비교는 불일치했으며, 이것만으로 버전 오류를 뜻하지 않는다. 비공개 로그에 명시적 systemid·버전 mismatch 문구는 없었다. 임시 OS Login 키 제거 성공. **클러스터 연결과 systemid는 정상; import 거절 원인은 추가 확인 필요**.
