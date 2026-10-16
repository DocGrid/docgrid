# Standby 재합류·WAL 수신 사후 점검 · POST-RESTORE-HEALTH-R1

## 실행 전 계획 (2026-10-17 03:04 KST)

| 항목 | 값 |
| --- | --- |
| 실행 ID | `DR-OFFSITE-20261017-POST-RESTORE-HEALTH-R1` |
| 목적 | 수동 원복 후 3노드 DB와 Barman WAL 수신이 정상인지 검증 |
| 위치·방법 | 백업 VM에서 DB 노드별 역할·복제 수신·지연, Barman check, WAL follower·timer를 읽기 전용 조회 |
| 합격 기준 | 리더 1·streaming Standby 2, lag 16 MiB 미만, Barman 수신 관련 3검사 OK, follower aligned |
| 보호 | 서버 주소·프로젝트·슬롯명·WAL LSN은 공개 로그에 기록하지 않고 집계와 상태만 기록 |

## 관측

2026-10-17 **03:04:09~03:04:20 KST**에 백업 VM에서 세 DB 노드를 조회했다. 리더 **1대**, streaming Standby **2대**, 두 Standby의 WAL 지연 **0·0바이트**였다. Barman 기준 백업 **1개**, `PostgreSQL streaming`·`replication slot`·`receive-wal running` **3/3 OK**, 리더 추적기 `aligned`, WAL 추적 timer 활성 상태였다. 임시 OS Login 키 제거 성공. **사후 복제·수신 점검 통과**.
