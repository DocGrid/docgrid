# 새 timeline을 가로지르는 격리 시점 복원

## 결과와 증거 범위

2026-10-16 KST, GCP 시험용 OpenSQL 3노드 중 Standby 1대를 잠시 중단하고 **기존 데이터 디렉터리와 다른 경로, `127.0.0.1:15432`**에서 기준 백업을 복원했다. 기준 백업은 timeline 18에서 생성됐고, Barman에 보관된 timeline 19·20의 WAL을 적용했다. 복원 목표 시각은 시험용 기록 A의 커밋 뒤, 기록 B의 커밋 전으로 고정했다. 최종 격리 SQL은 **A 1건, B 0건, 복구 모드 종료**를 확인했다. 격리 서버의 승격 뒤 checkpoint는 timeline 21이었다.

이 문서는 도구 출력으로 관측한 내용을 **사후에 비식별 요약한 기록**이다. 원격 명령의 전체 stdout 원본을 지속 파일로 저장한 실시간 로그는 없다. 따라서 아래 수치와 시각은 도구 출력에서 확인한 범위로 한정하며, 없는 샘플을 보완해 만들지 않았다. 내부 주소, 호스트명, 프로젝트 ID, 계정 및 라이선스 경로는 기록하지 않았다.

| 항목 | 사전 기준·방법 | 최종 관측 |
| --- | --- | --- |
| Run ID | `DR-BARMAN-PITR-CONTAINER-20261016-R3` | 23:18:15~23:18:33 KST |
| 복원 원본 | Barman 기준 백업 1개, timeline 18 | `verify-backup` 통과, timeline 18·19·20의 WAL/history 9개를 분리된 경로에 준비 |
| 복원 목표 | A 뒤·B 전, 2026-10-16 13:32:35.554380 UTC | A 1건, B 0건 |
| 격리 조건 | Standby 1대 정지, 원본 경로 보존, localhost 포트 15432 | 격리 서버 시작 0.285초, `pg_is_in_recovery=false` |
| 안전한 복귀 | 격리 서버 종료, 원래 컨테이너·Patroni 재시작 | 최종 Primary 1대, streaming Standby 2대, 복제 지연 0바이트 |
| 백업 수신 | 재합류 후 세 Barman 검사 | 3/3 OK, 감시기 모델 정렬 |

## 실행 순서와 실패

1. live Primary에 별도 `run_id`를 붙인 A·B를 기록했다. A는 `13:32:32.445829 UTC`, B는 `13:32:38.671865 UTC`였고, 목표 시각은 그 사이 `13:32:35.554380 UTC`로 고정했다. live DB에는 2건이 있었다. `barman switch-wal --archive` 뒤 세 Barman 검사는 OK였고 timeline 20의 완성 WAL을 확인했다.
2. Barman 복원에 `--target-tli 20 --target-time ... --target-action promote`를 사용했다. 첫 파일 복원은 `--no-get-wal`로 인해 `pg_wal`이 비어 통과하지 못했다. 보관된 WAL/history 9개, 총 83,889,096바이트를 별도 경로에 준비했고 해시 불일치 0건을 확인했다. 전송 시도 3회는 헤더/파이프 문제로 실패했고, 네 번째 압축 전송 32,385,649바이트가 성공했다.
3. **첫 안전 장치가 불완전했다.** 첫 15분 원복 타이머는 컨테이너를 `docker start`만 하도록 구성했다. 실제 중단 후 격리 호스트 PostgreSQL 실행은 라이선스 오류로 실패했고, 원래 컨테이너는 기본 명령 `sleep infinity`로만 기동되어 Patroni·PostgreSQL은 자동 재개되지 않았다. 22:50:57~22:51:05의 첫 시도 후 22:57:27~22:57:42에 올바른 bootstrap 명령을 직접 실행해 Standby를 재합류시켰다. 이 구간에는 **Standby 1대가 정상 복제에 참여하지 못했다.** Primary와 다른 Standby의 서비스는 유지됐다. 첫 타이머는 제거했다.
4. 이후 15분 원복 타이머를 컨테이너 재시작과 실제 bootstrap 실행을 포함하도록 수정했다. 호스트에서 직접 실행한 시도는 라이선스 오류로 실패했고, 컨테이너 내부 실행도 기존 DB 프로세스의 라이선스 환경을 전달하지 않아 실패했다. 두 시도 모두 수정한 원복 경로로 재합류했다. 라이선스 **값이나 경로는 출력하거나 기록하지 않고**, 실행 중인 기존 DB 프로세스의 환경에서 경로만 원격 메모리 안에서 읽어 격리 실행에 전달했다.
5. 라이선스를 전달한 첫 컨테이너 실행에서는 A 1건·B 0건이었으나 즉시 조회한 `pg_is_in_recovery=true` 때문에 통과로 판정하지 않았다. 이후 별도 조회에서 `recovery.signal` 제거를 확인했고, 다시 기동한 실행에서 A 1건·B 0건·`pg_is_in_recovery=false`를 확인했다. 마지막 stage 점검은 timeline 21 checkpoint와 새 WAL 1개를 확인했다.
6. 모든 원복 타이머를 해제하고, live DB에서 시험용 A·B만 2건 삭제해 잔여 0건을 확인했다. 격리 Standby stage 1,918파일과 Barman 임시 복원 stage 1,912파일을 각각 제거했다. Barman 기준 백업 1개와 WAL archive는 유지했다. 최종 점검은 Primary 1대·Standby 2대 streaming·복제 지연 0바이트·Barman 검사 3/3 OK였다.

7. stage 정리 뒤 23:28:19~23:28:30 KST에 [상태를 재확인](pitr-final-rejoin-r2.md)했다. Primary 1대·Standby 2대 streaming·lag 0바이트, Barman 검사 3개 OK, 감시기 timer 활성·모델 정렬이었다.

## 실행별 기록

| Run ID | 목적·위치·방법 | 관측·판정 |
| --- | --- | --- |
| `DR-BARMAN-PITR-ISOLATED-20261016-R1` | GCP 시험 Standby의 별도 데이터 경로, 호스트 PostgreSQL 실행 | 라이선스 오류·원복 타이머 불완전, **실패**. 수동 bootstrap 뒤 재합류 |
| `DR-BARMAN-PITR-CONTAINER-20261016-R1` | 같은 Standby의 기존 컨테이너 내부 실행 | 라이선스 경로 미전달로 **실패**, 수정한 원복 절차로 재합류 |
| `DR-BARMAN-PITR-CONTAINER-20261016-R2` | 동일 Standby의 라이선스 전달 컨테이너 실행 | A 1·B 0, 즉시 조회에 복구 모드 true, **부분 통과**. 원복 후 재합류 |
| `DR-BARMAN-PITR-CONTAINER-20261016-R3` | 동일 경로 재기동 후 SQL 판정 | A 1·B 0, 복구 모드 false, **통과**. 원복 후 재합류 |
| `DR-BARMAN-PITR-FINAL-REJOIN-20261016-R1` | GCP 3노드·백업 VM 최종 상태 | Primary 1, Standby 2 streaming/lag 0, Barman 3/3 OK, **통과** |
| `DR-BARMAN-PITR-FINAL-REJOIN-20261016-R2` | 격리 stage 정리 후 재확인 | Primary 1, Standby 2 streaming/lag 0, Barman 3검사 OK·timer 활성, **통과** |
| `DR-BARMAN-PITR-STAGE-FINAL-20261016-R1` | 복원 stage 읽기 전용 점검 | 파일 1,918개·302,224,459바이트, WAL/history 11개, timeline 21 checkpoint, **통과** |
| `DR-BARMAN-PITR-MARKER-CLEANUP-20261016-R1` | live Primary 시험 기록 정리 | 대상 2·삭제 2·잔여 0, **통과** |
| `DR-BARMAN-PITR-STAGE-CLEANUP-20261016-R1` | Standby 격리 stage 제거 | 1,918파일 제거, 기존 데이터 경로와 Patroni 유지, **통과** |
| `DR-BARMAN-PITR-BACKUP-STAGE-CLEANUP-20261016-R1` | Barman 임시 stage 제거 | 1,912파일 제거, 기준 백업 1개와 WAL archive 유지, **통과** |

각 원격 Run 종료 시 임시 OS Login 키 제거 명령의 성공을 확인했다. 이는 기존 키까지 포함한 독립적인 잔여 0건 검사를 뜻하지 않는다. 시험 중 GCS 복원, 다운로드·검색·업로드, RPO·RTO는 새로 측정하지 않았다. DB·GCS 사용자 경로는 [이전 서비스 복원 시험](../db-gcs-service-recovery-20261015/README.md)의 범위다.
