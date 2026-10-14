# DR-WAL-PREFLIGHT-20261015-R2

- 목적: 실제 Primary를 포함한 DB 3노드의 WAL 보관 가능 여부 확인.
- 위치·방법: 로컬 GCP CLI → DB VM 3대에 같은 30분 만료 OS Login SSH 키 → PostgreSQL의 역할·아카이브 설정·`pg_stat_archiver` 읽기. 노드명은 별칭으로만 기록.
- 기준: Primary에서 사용 가능한 아카이브 명령과 실제 WAL 보관 이력이 확인되어야 PITR 준비 통과.
- 시각: 실행 중 정확한 시각은 수집하지 못함. 2026-10-15 01:59 KST에 사후 기록.
- 관측: 세 노드의 SSH·SQL 조회 종료 코드 0/0/0. 별칭 1=Standby, 2=Primary, 3=Standby. 세 노드 모두 `archive_mode=on`, 아카이브 명령 비활성, 보관 수 0, 마지막 보관 시각 없음.
- 판정: **NO-GO**. 현재 Primary에서 연속 WAL 보관이 확인되지 않아 특정 시점 복원 시험을 실행할 근거가 없다.
- 정리: OS Login 공개키 삭제 종료 코드 0, 로컬 개인키·공개키 삭제 완료. 설정·데이터 변경 없음.
