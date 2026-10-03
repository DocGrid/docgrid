# OpenSQL primary 장애 시험 사전 점검

- 실행 ID: `ha405-preflight-20261003`
- 실행일: 2026-10-03 KST. 각 원격 조회의 절대 시각은 별도로 수집하지 않아 분 단위 타임라인으로 사용하지 않는다.
- 목적: 쓰기 부하 또는 장애 주입 전에 승인된 시험 계정과 DB·etcd·앱 경로의 현재 상태를 확인한다.
- 위치: 로컬 `gcloud` 읽기 전용 조회, GCP DB VM 3대의 IAP SSH를 통한 읽기 전용 수집.
- 성공 기준: 승인 계정, primary 1·streaming replica 2, 후보 lag 0 MB·`nofailover=false`, etcd endpoint 3/3, DB 디스크별 READY snapshot, 내부 앱 LB 2/2 HEALTHY.

| 점검 | 비식별 명령·방법 | 관측 결과 | 해석 |
| --- | --- | --- | --- |
| GCP 대상 | 활성 계정 핸들 비교, `gcloud compute instances list` | 요청한 계정 핸들 일치, DB VM 3/3 RUNNING·앱 VM 2/2 RUNNING·공용 캐시 VM 1/1·k6 VM 1/1 | 프로젝트 ID·계정명·VM 이름은 기록하지 않았다. |
| DB 역할·복제 | 설치된 수집기의 `patronictl list --format json`과 고정 SQL health 조회 | leader 1·streaming replica 2, timeline 5, 두 replica lag 0 MB; primary의 streaming 송신 2, replica 각각 수신 1 | 역할·복제 게이트 통과. 앱 HTTP 쓰기 성공과는 별개다. |
| 승격 제한·동적 설정 | 세 노드 로컬 Patroni REST의 역할·tag, 설치된 수집기의 비식별 설정 | node1 primary, node2/3 replica; `nofailover=false` 3/3, `ttl=30`, `failsafe_mode=true`, `maximum_lag_on_failover=1048576` | 정족수 상실·VM 장애 결과는 설정에 따라 별도로 판정한다. |
| etcd | 세 노드 각각 로컬 `etcdctl endpoint health`, 수집기의 멤버 목록 | endpoint healthy 3/3, 멤버 3, 정족수 2 | 각 멤버의 로컬 응답 확인. |
| 백업 | `gcloud compute snapshots list`와 현재 부착 부팅 디스크 참조 정확히 대조 | DB 부팅 디스크 3개 모두 READY snapshot으로 커버; 일치 READY snapshot 총 7개, 48시간 이내 7개 | 복구 지점 존재 확인. 이번 실행에서 전체 클러스터 복원은 하지 않았다. |
| 앱 경로 | 내부 HTTP LB의 backend health API | backend 2/2 HEALTHY | HTTP 쓰기나 앱 JAR 동일성은 뒤따르는 기준선에서 별도 확인한다. |
| 접근 안전 | 인스턴스 전용 만료형 공개키·IAP SSH | DB VM 3/3, k6 VM 1/1 접속 성공 | 임시 키는 시험 후 정확히 제거한다. 기존 프로젝트 공통 키·방화벽은 변경하지 않았다. |

복제 위치 재확인: 2026-10-03 22:19 KST경, 세 VM에서 `sudo /usr/local/sbin/docgrid-permission-node-probe lsn <node>`를 차례로 실행했다. primary current WAL, 두 standby receive/replay WAL이 모두 `0/D064238`이었다. 위 lag 0 MB는 이 재확인 시점의 관측이며, 시험 도중 계속 0이라는 뜻은 아니다.

판정: **읽기 전용 환경 게이트 통과.** 합성 계정은 세 VM 독립 정리 타이머 3/3을 먼저 무장한 뒤 4명 생성했다. 이 단계에서는 k6 부하·switchover·프로세스 종료·VM 상실을 실행하지 않았다.
