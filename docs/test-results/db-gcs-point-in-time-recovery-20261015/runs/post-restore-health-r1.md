# DR-POST-RESTORE-HEALTH-20261015-R1 — 운영 상태 사후 점검

- 목적·성공 기준: Standby 복귀 후 각 DB 노드에서 리더 1·스트리밍 Standby 2·로컬 etcd 정상이고, 백업 WAL 수신이 이어지며 외부 IP·임시 NAT가 없어야 한다.
- 위치·절차: GCP 읽기 전용 인스턴스 메타데이터, 3개 DB 노드의 로컬 Patroni `/cluster`·etcd `/health`, 백업 VM의 Barman `check`·정확한 `pg_receivewal` 프로세스 수, Primary 복제 슬롯 SQL 조회.
- 시각: 2026-10-15 약 03:43~03:45 KST. 개별 네트워크 호출의 정확한 시작·종료 시각은 별도 캡처하지 않았다.
- 관측: 세 DB 노드 각각 Patroni 구성원 3·리더 1·스트리밍 Standby 2, 로컬 etcd 정상. Barman PostgreSQL streaming·receive-wal running·minimum redundancy 항목 `OK`, 수신 프로세스 1개. 슬롯 `active=true`, `wal_status=reserved`, 현재 WAL 차이 20,992바이트, 당시 safe WAL size 4,311,723,520바이트, 설정 상한 4GB. 백업 VM 외부 IP 0개, 임시 NAT 없음.
- 계측 수정: 앞선 상태 점검의 `pgrep`은 프로세스 명칭을 잘못 지정해 0을 표시했다. 정확한 `pg_receivewal` 이름으로 재조회해 1개를 확인했다. Barman `receive-wal running=OK`도 별도 확인했다.
- 판정: **시험 직후 클러스터·백업 수신 상태 통과.** 이는 장기 모니터링이나 Primary 전환 후 자동 복구를 증명하지 않는다.
