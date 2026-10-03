# DB 조회 실패 차단 패치 배포·사후 확인

- 실행 ID: `fixture-397-deploy-12`
- 시작: 2026-10-03 14:26 KST
- 목적: 로컬에서 통과한 `role`·`guard-status` 오류 전파 수정본을 세 DB VM에 동일하게 배포하고, 서비스 정상 상태에서 각 명령이 유효한 숫자를 반환하는지 확인한다.
- 위치: GCP DB VM 3대; 승인된 임시 인스턴스 전용 SSH 키와 IAP 전송을 사용한다.
- 방법: 각 노드의 기존 도우미를 별도 파일로 보존 → 수정본 전송·권한 0750 설치 → 로컬/원격 SHA-256 일치 확인 → 역할·계정 수·타이머 상태 조회.
- 성공 기준: 해시 3/3 일치, 단일 primary·streaming replica 2, 각 노드 `armed=false fixture_count=0`, etcd endpoint 3/3 healthy.
- 제한: DB를 다시 중단시켜 실환경 실패 경로를 재현하지 않는다. 실패 경로는 별도 로컬 결정적 시험에서 검증했다.

| 단계 | 관측 결과 | 판정 |
| --- | --- | --- |
| 세 VM 교체 배포 | 기존 스크립트를 노드별로 별도 보존. 수정본 설치 후 로컬 소스와 원격 SHA-256 일치 **3/3**; 중간 전송 파일 삭제 | 동일 버전 배포 통과 |
| 수정본 `role` | node1·node3=`replica`, node2=`primary`, 명령 종료 코드 0 | 단일 primary 확인 |
| 수정본 `guard-status` | 세 노드 모두 `armed=false fixture_count=0`, 명령 종료 코드 0 | 계정 0명·타이머 0/3 |
| Patroni·etcd | leader 1·streaming replica 2, timeline 2; etcd endpoint **3/3 healthy** | node1 수동 복구 후 클러스터 정상 구성 |

최종 판정: **정상 상태 배포·검증 통과**. 이전 리더가 `stopping`이었던 동안의 잘못된 성공 출력은 [역할 이전 로그](10-live-planned-switchover.md)에 그대로 보존했다. 수정본의 DB 실패 차단은 [로컬 결정적 시험](11-local-query-failure-regression.md)으로 확인했다.
