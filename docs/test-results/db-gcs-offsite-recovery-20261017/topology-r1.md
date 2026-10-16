# DB 토폴로지와 백업 수신 사전 점검 · TOPOLOGY-R1

## 실행 전 계획 (2026-10-17 02:34 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-TOPOLOGY-R1` |
| 목적 | 격리 복원 호스트를 선택하기 전에 클러스터의 정상 복제·Barman WAL 수신 확인 |
| 위치·방법 | 기존 백업 VM에서 세 DB 노드의 SQL 상태, LSN 지연, Barman 검사, 리더 추적 timer를 읽기 전용 조회 |
| 합격 기준 | Primary 1대, streaming Standby 2대, 두 지연 각각 16MiB 미만, Barman 관련 검사 OK, timer 활성·리더 정렬 |
| 보호 | 내부 주소·계정·접속 문자열은 출력하지 않고 노드 순서와 수치만 기록. 임시 OS Login 키 제거 |

## 관측

2026-10-17 **02:33:10~02:33:21 KST**에 읽기 전용 점검을 완료했다. Primary **1대**, streaming Standby **2대**, 각각 복제 지연 **0바이트**였다. 완료 기준 백업 **1건**, Barman의 streaming·slot·receive-WAL 검사 **3/3 OK**, 리더 추적 timer 활성·리더 정렬을 확인했다. 임시 OS Login 키 제거 성공. **통과**. 이 관측은 외부 WAL의 목표 시점 포함 여부를 입증하지 않는다.
