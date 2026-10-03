# 세 노드 시험 계정 수동 정리 연습 실행 로그

- 실행 ID: `b447ad2079bb`
- 실행 시각: 2026-10-03 13:45~13:51 KST. 각 개별 명령의 정확한 시작 시각은 수집하지 못했다.
- 환경: GCP Rocky Linux 9.7 DB VM 3대, 현재 primary 1·streaming replica 2. PR #398의 정리 도우미 설치 SHA-256은 세 VM 모두 로컬 소스와 일치했다.
- 목적: 장애 없이 독립 타이머 3/3 → 합성 계정 4명 생성 → 현재 primary 삭제 → 복제·타이머 원복을 실제 실행한다.
- 실행 방법: 각 노드에서 `docgrid-permission-fixture role|guard-status|arm|cancel`; 현재 primary에서 `stale-count|create|remove`. 모든 원격 명령은 임시 만료형 SSH 키와 기존 고정 호스트키를 통한 IAP로 실행했다.
- 성공 기준: 타이머 3/3 무장 전 생성 금지, 세 DB에서 계정 4명 확인, 삭제 후 세 DB 계정 0명·타이머 0/3.
- 출력 제한: 이메일·DB ID·키·프로젝트·내부 주소는 출력하지 않았다.

| 단계 | 관측 숫자·상태 | 판정 |
| --- | --- | --- |
| 배포·사전 검사 | 기존 node1 도우미는 저장소 기준 버전과 일치. 세 VM 도우미 배포 SHA-256 일치 3/3. sudo 3/3, DB 컨테이너 실행 3/3, 기존 타이머 0/3, 기존 합성 ADMIN 잔여 0명 | 시작 관문 통과 |
| 타이머 무장 | `arm` 성공 3/3; 각 노드 `armed=true fixture_count=0`; 역할 primary 1·replica 2 | 생성 관문 통과 |
| 계정 생성 | 현재 primary의 `create` 성공, 기대 형식 4행. 세 노드 모두 `armed=true fixture_count=4` | 생성·복제 관측 |
| 수동 삭제 | primary의 `remove` 성공. 세 노드 모두 `armed=true fixture_count=0` | 삭제·복제 관측 |
| 타이머 해제·재확인 | `cancel` 성공 3/3. 세 노드 모두 `armed=false fixture_count=0`; 역할 primary 1·replica 2 유지 | **통과**. 계정 0명·타이머 0/3 |

제한: 이 실행은 장애 없는 연습이며 리더 이동·VM 상실 때의 계정 정리를 증명하지 않는다. 오퍼레이터 Python 도우미의 `gcloud compute ssh` 경로 대신, 승인된 인스턴스 전용 임시 키의 direct IAP SSH로 같은 순서를 수동 실행했다. 따라서 Python 도우미의 실서버 호출 자체는 별도 검증이 필요하다.
