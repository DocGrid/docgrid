# DR-WAL-PREFLIGHT-20261015-R1

- 목적: DB 노드의 실제 PostgreSQL WAL 아카이브 설정 확인.
- 위치·방법: 로컬 GCP CLI → DB VM 한 대에 30분 만료 OS Login SSH → 컨테이너 안에서 PostgreSQL 설정 및 `pg_stat_archiver` 읽기. 계정·VM·주소·명령의 비밀 값은 출력하지 않음.
- 기준: 아카이브 명령과 보관 건수 확인; 실제 Primary 여부는 후속 회차에서 확인.
- 시각: 실행 중 정확한 시각은 수집하지 못함. 2026-10-15 01:59 KST에 사후 기록.
- 관측: SSH·SQL 조회 종료 코드 0. `archive_mode=on`, 아카이브 명령 비활성, `wal_level=replica`, 보관 수 0, 마지막 보관 시각 없음.
- 판정: 조회한 노드에서 WAL 아카이브 증거 없음. Primary 여부를 수집하지 않아 클러스터 전체 결론은 R2에서 확인.
- 정리: OS Login 공개키 삭제 종료 코드 0, 로컬 개인키·공개키 삭제 완료.
