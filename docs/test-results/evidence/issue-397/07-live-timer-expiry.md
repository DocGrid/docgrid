# 시험용 계정 독립 정리 타이머 실환경 실행 로그

- 실행 ID: `3c9a99125769`
- 시작: 2026-10-03 13:51:41 KST
- 환경: GCP Rocky Linux 9.7 DB VM 3대, OpenSQL/Patroni 1 primary·2 streaming replica, PR #398 스크립트 배포 해시 3/3 일치
- 목적: 세 VM의 300초 독립 타이머를 무장하고 합성 계정 4명을 만든 뒤, 수동 삭제 없이 실제 타이머가 계정을 정리하는지 확인한다.
- 실행 방법: 임시 만료형 SSH 키와 고정된 호스트키를 통한 IAP 접속. 각 노드의 `docgrid-permission-fixture arm|guard-status`, 현재 primary의 `create`, 만료 후 각 노드의 `guard-status`를 사용한다.
- 성공 기준: 생성 후 3노드 계정 4명·타이머 3/3, 타이머 실행 후 3노드 계정 0명, 잔여 타이머 안전 해제. 실패 실행도 별도로 기록한다.
- 기록 원칙: 이메일·DB ID·GCP 프로젝트·내부 주소·SSH 키는 수집·출력하지 않는다. 아래 숫자와 역할만 기록한다.

| 시각 (KST) | 단계 | 관측 결과 | 판정 |
| --- | --- | --- | --- |
| 13:51:41 | 실행 준비 | 다른 실행의 계정 0명·타이머 0/3, 새 실행 ID 생성 | 시작 |
| 13:52경 | 타이머 무장 | 세 VM 모두 `arm` 종료 코드 0. `guard-status`는 모두 `armed=true fixture_count=0`; DB 역할은 primary 1·replica 2 | 계정 생성 관문 통과 |
| 13:52:56 | 계정 생성 | 현재 primary의 `create` 종료 코드 0, 기대 형식 계정 4명 반환 | 생성 성공 |
| 13:53:24 | 복제 확인 | 세 VM 모두 `armed=true fixture_count=4` | 3/3 복제 관측. 타이머 만료는 아직 관측 전 |
| 13:56:44 | 만료 전 재확인 | 세 VM 모두 `armed=true fixture_count=4` | 300초 타이머가 아직 실행되지 않은 상태 |
| 13:58:17 | 만료 후 재확인 | 세 VM 모두 `armed=true fixture_count=0`. primary의 정리 service는 `success`, 두 replica의 service는 `exit-code`이고 timer는 모두 `active` | primary 타이머가 수동 삭제 없이 계정 4명을 정리함. replica의 재시도 예약 상태는 관측했지만 재실행 자체는 아직 미확인 |
| 13:59경 | replica 재시도 기록 | 각 replica의 해당 service journal에서 `Local DB is not primary` 문구 **2회** 확인 | 한 번 이상 재시도한 것으로 관측. 서비스의 오류 내용이나 식별자는 기록하지 않음 |
| 13:59:56 | 원복 완료 | 세 VM 모두 `armed=false fixture_count=0`; DB 역할 primary 1·replica 2 유지 | 자동 삭제 4명, 잔여 계정 0명, 반복 타이머 0/3 |

최종 판정: **통과**. 실제 300초 타이머가 현재 primary에서 계정을 자동 삭제했다. replica의 실패 후 반복 시도도 관측했다. 다만 **리더 이동 후 새 primary가 삭제하는 경로는 이 실행에서 시험하지 않았다.**
