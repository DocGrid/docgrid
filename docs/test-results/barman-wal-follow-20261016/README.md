# Barman WAL 수신 대상 전환과 계획 리더 변경 검증

## 범위

2026-10-16 KST에 분리 작업 트리와 GCP 시험용 OpenSQL 3노드·백업 VM에서 리더 식별, 세 노드 복제 접속, 영구 슬롯, Barman 모델 전환, 1분 주기 감시기와 **계획 Primary 전환 1회**를 검증했다. 전환 직후에는 Barman 슬롯·수신 검사가 실패했지만 주기 감시기가 새 리더 모델로 전환한 뒤 통과했다. 새 timeline 20의 history와 완성 WAL을 백업 저장소에서 회수했다. 이어서 timeline 18의 기준 백업과 timeline 19·20의 WAL로 **격리 PITR을 실행**, 목표 시각 전후 시험 기록 A 1건·B 0건과 복구 모드 종료를 확인했다. 첫 자동 원복 절차의 결함으로 시험 Standby 1대의 재합류가 지연됐고, 수정한 절차로 복구·재시험했다.

백업 VM은 외부 IP 없이 유지했고, DB의 기존 Patroni 관리 포트는 열지 않았다. 백업 VM의 단일 내부 주소에서 DB 3노드의 지표 전용 TCP 18008로 가는 허용 규칙을 추가했다. 기존 차단 규칙보다 높은 우선순위 800으로 조정한 후에만 실제 접근이 가능했다. 실제 주소·프로젝트·호스트·계정은 실행 기록에 넣지 않았다.

| Run ID | 목적·위치 | 관측 | 판정 |
| --- | --- | --- | --- |
| [`DR-BARMAN-GATEWAY-20261016-R1`](gateway-r1.md) | 백업 VM → 지표 경로 | 3노드 중 접근 0 | 실패 |
| [`DR-BARMAN-NETWORK-20261016-R1`](network-r1.md) | 노드 식별과 경로 | DB 노드 식별 규칙 오류로 연결 시험 미수행 | 미판정 |
| [`DR-BARMAN-NETWORK-20261016-R2`](network-r2.md) | 백업 VM TCP 연결 | DB 5432는 3/3 접근, 지표 18008은 0/3 | 실패 |
| [`DR-BARMAN-FIREWALL-20261016-R1`](firewall-r1.md) | 제한된 허용 규칙 추가 | 규칙은 생성, 지표 응답 0/3 | 실패 |
| [`DR-BARMAN-FIREWALL-20261016-R2`](firewall-r2.md) | 허용 규칙 재조회 | 기존 차단과 같은 우선순위 1000 때문에 0/3 시간 초과 | 실패 |
| [`DR-BARMAN-FIREWALL-20261016-R3`](firewall-r3.md) | 우선순위 조정 후 HTTP | HTTP 200 3/3; 시험용 파서가 라벨 지표를 인식하지 못함 | 부분 통과 |
| [`DR-BARMAN-FIREWALL-20261016-R4`](firewall-r4.md) | 라벨 지표 역할 판정 | HTTP 200 3/3, 실행 중 3/3, Primary 1/3 | 통과 |
| [`DR-BARMAN-UNIT-20261016-R1`](unit-r1.md) | 로컬 단위 시험 | 결과 파일 저장 실패 | 미판정 |
| [`DR-BARMAN-UNIT-20261016-R2`](unit-r2.md) | 로컬 단위 시험 재실행 | 당시 단위 시험·문법·공백 검사 종료 코드 0 | 통과 |
| [`DR-BARMAN-UNIT-20261016-R3`](unit-r3.md) | 메트릭·정족수 보강 후 로컬 재검증 | 단위 시험 9건·문법·공백 검사 종료 코드 0 | 통과 |
| [`DR-BARMAN-UNIT-20261016-R4`](unit-r4.md) | 모델 적용 실패·동일 호스트 초기화 회귀 | 단위 시험 11건·문법·공백 검사 종료 코드 0 | 통과 |
| [`DR-BARMAN-DRYRUN-20261016-R1`](dryrun-r1.md) | 백업 VM 읽기 전용 감시기 | 원격 호출 인자 오류로 코드 실행 전 실패 | 실패 |
| [`DR-BARMAN-DRYRUN-20261016-R2`](dryrun-r2.md) | 백업 VM 읽기 전용 감시기 | 3노드 관측, 리더 1대 식별, 모델 전환 필요 판정 | 통과 |
| [`DR-BARMAN-DBACCESS-20261016-R1`](db-access-r1.md) | 백업 계정의 세 노드 SQL 접속 | 현재 Primary 1/3만 성공 | 실패 |
| [`DR-BARMAN-DBACCESS-20261016-R2`](db-access-r2.md) | 실패 유형 분류 | Standby 2대는 비밀번호 누락 범주 | 원인 분류 |
| [`DR-BARMAN-DBACCESS-20261016-R3`](db-access-r3.md) | 비공개 인증 소스 사용 제안 | 자동 승인 검토가 실행 전에 거부 | 차단·미실행 |
| [`DR-BARMAN-DBACCESS-20261016-R4`](db-access-r4.md) | 승인 후 SQL 접속 재시험 | Barman 서버 인자 누락으로 DB 접속 전 실패 | 실패 |
| [`DR-BARMAN-DBACCESS-20261016-R5`](db-access-r5.md) | 서버 목록 파싱 수정 | 출력 형식 가정 오류로 DB 접속 전 실패 | 실패 |
| [`DR-BARMAN-DBACCESS-20261016-R6`](db-access-r6.md) | 기존 인증으로 세 DB 노드 SQL 접속 | 3/3 성공, Primary 1·Standby 2 | 통과 |
| [`DR-BARMAN-REPLICATION-20261016-R1`](replication-r1.md) | 세 DB 노드 복제 프로토콜 접속 | Primary 1/1, Standby 0/2 | 실패 |
| [`DR-BARMAN-SLOT-POLICY-20261016-R1`](slot-policy-r1.md) | Patroni 동적 슬롯·DB 로컬 조회 | 영구 슬롯 0; 로컬 SQL 조회 실패 | 부분 확인 |
| [`DR-BARMAN-SLOT-POLICY-20261016-R2`](slot-policy-r2.md) | Barman SQL로 WAL 보존·슬롯 상태 확인 | 보존 한도 4GB, 물리 슬롯 3개 중 활성 3개 | 통과 |
| [`DR-BARMAN-REPLICATION-20261016-R2`](replication-r2.md) | Standby 실패 범주 재확인 | GCP 인스턴스 후보 0건으로 원격 접속 전 종료 | 미실행 |
| [`DR-BARMAN-REPLICATION-20261016-R3`](replication-r3.md) | Standby 복제 인증 분류 | HBA 거부로 원인 분류 | 실패 |
| [`DR-BARMAN-HBA-APPLY-20261016-R1`](hba-apply-r1.md) | Standby 복제 HBA 보강 | 백업 VM 한정 규칙 2대 적용·재로드 | 통과 |
| [`DR-BARMAN-REPLICATION-20261016-R4`](replication-r4.md) | 복제 프로토콜 재시험 | `IDENTIFY_SYSTEM` 3/3 성공 | 통과 |
| [`DR-BARMAN-PGPASS-20261016-R2`](pgpass-r2.md) | 지속 인증 재시험 | SQL·복제 접속 3/3 | 통과 |
| [`DR-BARMAN-SLOT-CAP-APPLY-20261016-R1`](slot-cap-apply-r1.md) | 세 노드 슬롯 WAL 보존 상한 | 실제 설정 4GB 3/3 | 통과 |
| [`DR-BARMAN-PERMANENT-SLOT-20261016-R1`](permanent-slot-r1.md) | 첫 영구 슬롯 등록 | 잘못된 중첩 키로 Standby 슬롯 미생성 | 실패 |
| [`DR-BARMAN-PERMANENT-SLOT-20261016-R2`](permanent-slot-r2.md) | 최상위 영구 슬롯 등록 | Patroni DCS 적용 후 실제 슬롯 3/3 | 통과 |
| [`DR-BARMAN-SLOT-TOPOLOGY-20261016-R7`](slot-topology-r7.md) | 물리 슬롯 확인 | 세 노드 존재, 리더에서 활성 | 통과 |
| [`DR-BARMAN-MODEL-INSTALL-20261016-R1`](model-install-r1.md) | 구성 모델·감시기 설치 | 모델 3개, Barman 검사 3/3 OK | 통과 |
| [`DR-BARMAN-WATCHER-INIT-20261016-R1`](watcher-init-r1.md) | 현재 리더로 모델 초기화 | 1회 전환·검사 3/3 OK | 통과 |
| [`DR-BARMAN-WATCHER-INIT-20261016-R2`](watcher-init-r2.md) | 반복 실행 | 재전환 없음·검사 3/3 OK | 통과 |
| [`DR-BARMAN-TIMER-INSTALL-20261016-R1`](timer-install-r1.md) | 1분 주기 감시기 활성화 | timer active·service 성공 | 통과 |
| [`DR-BARMAN-SWITCHOVER-PREFLIGHT-20261016-R1`](switchover-preflight-r1.md) | 전환 전 안전 조건 | Standby 2/2 streaming·지연 0바이트·Barman 정상 | 통과 |
| [`DR-BARMAN-SWITCHOVER-20261016-R1`](switchover-r1.md) | 첫 전환 시도 | 멤버명 가정 오류로 명령 전 중단 | 실패·DB 변경 없음 |
| [`DR-BARMAN-PATRONI-MAPPING-20261016-R1`](patroni-mapping-r1.md) | Patroni 멤버 매핑 | REST host로 노드 3/3 대응 | 통과 |
| [`DR-BARMAN-SWITCHOVER-PREFLIGHT-20261016-R2`](switchover-preflight-r2.md) | 수정 후 안전 조건 재확인 | Standby 2/2 streaming·지연 0바이트 | 통과 |
| [`DR-BARMAN-SWITCHOVER-20261016-R2`](switchover-r2.md) | 계획 리더 전환 | 순번 2→1, timeline 19→20, Standby 2/2 재합류 | 통과 |
| [`DR-BARMAN-SWITCHOVER-POSTCHECK-20261016-R1`](switchover-postcheck-r1.md) | 전환 직후 Barman 상태 | 슬롯·수신 실패, 모델 미정렬 | 실패 |
| [`DR-BARMAN-TIMER-JOURNAL-20261016-R1`](timer-journal-r1.md) | 감시기 로그 첫 조회 | VM 시간대 차이로 이벤트 0건 | 미확인 |
| [`DR-BARMAN-TIMER-JOURNAL-20261016-R2`](timer-journal-r2.md) | UTC 재조회 | 22:15:39 KST 모델 전환 이벤트 확인 | 통과 |
| [`DR-BARMAN-SWITCHOVER-POSTCHECK-20261016-R2`](switchover-postcheck-r2.md) | 주기 감시기 수렴 뒤 상태 | 모델 정렬, 세 Barman 검사 OK | 통과 |
| [`DR-BARMAN-WAL-CONTINUITY-20261016-R1`](wal-continuity-r1.md) | timeline 20 파일 | history 1개·partial 1개 | 부분 통과 |
| [`DR-BARMAN-WAL-ARCHIVE-20261016-R1`](wal-archive-r1.md) | 새 WAL 세그먼트 보관 | 완성 세그먼트 0→1, 검사 3/3 OK | 통과 |
| [`DR-BARMAN-WAL-RETRIEVE-20261016-R1`](wal-retrieve-r1.md) | Barman에서 새 WAL 회수 | history 820바이트·WAL 16MiB 읽기 성공 | 통과 |
| [`DR-BARMAN-REPORT-QA-20261016-R1`](report-qa-r1.md) | 로컬 문서·단위 시험 검증 | 시험 11/11, 링크·민감 패턴·공백 오류 0 | 통과 |
| [`DR-BARMAN-PITR-FILES-20261016-R1`](pitr-files-r1.md) | 기준 백업의 격리 파일 복원 | 기준 백업 검증 통과, `--no-get-wal`로 WAL 미포함 | 부분 통과 |
| [`DR-BARMAN-PITR-WAL-STAGE-20261016-R1`](pitr-wal-stage-r1.md) | timeline 18·19·20 WAL 준비 | 9파일·83,889,096바이트·해시 불일치 0 | 통과 |
| [`DR-BARMAN-PITR-TRANSFER-20261016-R1`](pitr-transfer-r1.md) | 격리 Standby로 stage 전송 | 스트림 헤더 문제 | 실패 |
| [`DR-BARMAN-PITR-TRANSFER-20261016-R2`](pitr-transfer-r2.md) | 전송 재시도 | 스트림 헤더 문제 | 실패 |
| [`DR-BARMAN-PITR-TRANSFER-20261016-R3`](pitr-transfer-r3.md) | 전송 재시도 | BrokenPipe | 실패 |
| [`DR-BARMAN-PITR-TRANSFER-20261016-R4`](pitr-transfer-r4.md) | 전송 네 번째 시도 | 압축 스트림 32,385,649바이트 성공 | 통과 |
| [`DR-BARMAN-PITR-ISOLATED-20261016-R1`](pitr-isolated-r1.md) | 첫 격리 기동·자동 원복 | 라이선스 오류, 불완전한 타이머로 Standby 자동 재합류 실패 | 실패·수동 복구 |
| [`DR-BARMAN-PITR-CONTAINER-20261016-R1`](pitr-container-r1.md) | 컨테이너 격리 기동 | 라이선스 경로 미전달, 수정한 원복 절차는 성공 | 실패·원복 |
| [`DR-BARMAN-PITR-CONTAINER-20261016-R2`](pitr-container-r2.md) | 목표 시각 데이터 확인 | A 1·B 0, 첫 조회에서 복구 모드 true | 부분 통과 |
| [`DR-BARMAN-PITR-CONTAINER-20261016-R3`](pitr-container-r3.md) | 새 timeline 격리 PITR | A 1·B 0, 복구 모드 false | 통과 |
| [`DR-BARMAN-PITR-FINAL-REJOIN-20261016-R1`](pitr-final-rejoin-r1.md) | 원래 3노드 재합류 | Primary 1·Standby 2 streaming/lag 0·Barman 3/3 OK | 통과 |
| [`DR-BARMAN-PITR-STAGE-FINAL-20261016-R1`](pitr-stage-final-r1.md) | 격리 stage timeline 확인 | checkpoint timeline 21, 복구 신호 제거 | 통과 |
| [`DR-BARMAN-PITR-ROLLBACK-DISARM-20261016-R4`](pitr-rollback-disarm-r4.md) | 안전 타이머 정리 | 타이머·스크립트 제거 | 통과 |
| [`DR-BARMAN-PITR-MARKER-CLEANUP-20261016-R1`](pitr-marker-cleanup-r1.md) | live DB 시험 기록 정리 | 2건 삭제·잔여 0 | 통과 |
| [`DR-BARMAN-PITR-STAGE-CLEANUP-20261016-R1`](pitr-stage-cleanup-r1.md) | 격리 Standby stage 정리 | 1,918파일 제거·원본 유지 | 통과 |
| [`DR-BARMAN-PITR-BACKUP-STAGE-CLEANUP-20261016-R1`](pitr-backup-stage-cleanup-r1.md) | 백업 VM 임시 stage 정리 | 1,912파일 제거·기준 백업 1개 유지 | 통과 |
| [`DR-BARMAN-PITR-REPORT-QA-20261016-R1`](pitr-report-qa-r1.md) | 로컬 기록·링크·민감 패턴 확인 | 문서 19개, 링크·민감 패턴·공백 오류 0 | 통과 |
| [`DR-BARMAN-PITR-REPORT-QA-20261016-R2`](pitr-report-qa-r2.md) | 링크·민감 패턴·후행 공백 재검사 | 문서 20개, 오류 0 | 통과 |

SSH가 실행된 각 Run에서 임시 OS Login 키 제거 명령의 성공을 확인했다. 삭제 명령 성공이 계정의 기존 키까지 포함한 독립적 잔여 0건 검증을 뜻하지는 않는다.

## 관측과 남은 검증

전환 명령은 3.430초에 끝났다. [감시기 로그](timer-journal-r2.md)에 따르면 22:15:39 KST 새 리더 모델로 자동 전환했다. 그보다 앞선 첫 점검에는 Barman 수신이 아직 실패했고, 다음 점검에는 모델 정렬과 수신이 확인됐다. **정확한 WAL 수신 중단 시간과 RPO는 계측하지 않았다.** 영구 슬롯의 4GB WAL 보존 한도를 적용했으므로 수신 장기 중단과 디스크 사용량은 계속 감시해야 한다.

기준 백업 timeline 18에서 새로운 timeline 20의 목표 시각까지 복원하고 A 1건·B 0건을 확인한 내용은 [별도 PITR 결과](pitr-timeline20.md)에 있다. 첫 자동 원복 결함과 중간 실패도 함께 기록했다. **전환 전후 사용자 성공 응답 전체의 DB·GCS 원장 대조, 이 시험의 RPO·RTO, GCS 복원은 아직 수행하지 않았다.**

설계와 운영 조건은 [별도 문서](../../design/barman-wal-follow-primary.md)에 기록했다.
