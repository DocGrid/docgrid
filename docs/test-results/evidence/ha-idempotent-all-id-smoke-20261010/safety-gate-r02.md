# 리더 VM 장애 촬영 전 읽기 전용 안전 관문 r02

- 확인 시각: 2026-10-10 13:32 UTC 전후. 상태는 이 시각의 스냅샷이며 촬영 직전에 다시 확인해야 한다.
- 범위: 현재 앱 A/B, 내부 LB, DB 3대, OpenProxy 포트, etcd, DB 디스크 백업, 현재 리더 전용 Workflows 복구 가드. VM 중단·장애 주입은 하지 않았다.

| 관문 | 읽기 전용 방법 | 관측 결과 | 판정 |
| --- | --- | --- | --- |
| HA 대상 VM | Compute Engine 실행 상태 | 앱 2/2, DB 3/3, 부하 1/1, 관측 1/1 RUNNING | 충족 |
| 내부 LB | 백엔드 서비스 health | 앱 2/2 HEALTHY | 충족. HTTP 쓰기 단독 증거는 아님 |
| 앱·DB·프록시 관측 | Prometheus 즉시 쿼리 | 앱 `up` 2/2, Patroni `up` 3/3, PostgreSQL 실행 3/3, 프록시 TCP `probe_success` 2/2 | 충족. 수집·TCP 연결 범위만 뜻함 |
| DB 역할 | Patroni 지표 및 시험 DB 역할 검사 | primary 1·replica 2 | 충족 |
| etcd | 각 DB의 로컬 endpoint health 및 member list | endpoint 3/3 건강, 멤버 3·learner 0 | 충족 |
| DB 디스크 스냅샷 | 연결 디스크 source와 READY snapshot sourceDisk 정확 일치 | node1 5개, node2 3개, node3 3개 READY; 각 디스크 최신 약 9.5시간 전 | 백업 존재. 오늘 복원 시험은 미실행 |
| 독립 복구 Workflows | 배포된 고정 대상·zone·실행 계정과 execution 목록 | Workflow ACTIVE, 고정 대상은 조회 당시 현재 리더와 일치. **ACTIVE 지연 실행 0개** | 미충족 |
| 리더 전용 IAM | 현재 리더 인스턴스의 Workflows 계정 조건부 바인딩 | 바인딩은 있으나 시간 조건이 약 5.4시간 전에 만료 | 미충족 |

**종합 판정: 리더 VM 중단 NO-GO.** 워크플로가 존재한다는 것만으로 독립 복구가 무장된 것은 아니다. 현재 리더 전용 권한을 다시 제한적으로 부여하고, 충분한 지연의 Workflows 실행을 시작한 뒤 `ha_vm_guard_gate.py`에서 ACTIVE·대상 일치·남은 시간 120초 이상을 확인해야 한다. 최신 백업의 실제 복구 가능성과 수동 대체 경로도 촬영 직전에 별도 재확인한다. 이 문서는 IAM 갱신이나 Workflows 실행을 승인·수행한 기록이 아니다.
