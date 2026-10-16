# DR-BARMAN-REPLICATION-20261016-R4 — HBA 변경 후 복제 프로토콜 재시험

- 시작·종료: 2026-10-16 21:50:56~21:51:08 KST.
- 목적·위치: GCP 백업 VM의 Barman 계정에서 DB 3노드 복제 프로토콜 접속 재확인.
- 방법: 기존 인증 항목을 메모리에서만 읽어 `psql replication=true`의 `IDENTIFY_SYSTEM` 요청에 전달. 시스템 ID·WAL 위치·주소·비밀번호 미출력.
- 통과 기준: 3/3 응답과 동일 시스템 ID, 임시 OS Login 키 제거.
- 관측: Primary 1/1, Standby 2/2, 합계 3/3 성공. 세 응답의 시스템 ID가 같았다. 임시 키 제거 명령 성공.
- 판정: **통과**. 이 Run은 현재 세 노드의 복제 접근만 증명하며 새 리더에서 Barman WAL 수신이 이어진다는 뜻은 아니다.
