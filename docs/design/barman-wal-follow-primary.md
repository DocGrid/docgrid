# OpenSQL 리더 변경 후 Barman WAL 수신 대상 전환

## 현재 경계와 목표

[시점 복원 시험](../test-results/db-gcs-point-in-time-recovery-20261015/README.md)에서 Barman 기준 백업과 연속 WAL로 격리 DB를 복원했다. [서비스 경로 시험](../test-results/db-gcs-service-recovery-20261015/README.md)에서는 복원 DB·GCS로 다운로드·검색·업로드를 확인했다. 그러나 Barman의 `conninfo`와 `streaming_conninfo`는 시험 당시 Primary 한 대를 직접 가리켰다. 노드 재시작 후 슬롯과 수신 프로세스를 수동으로 복구한 기록도 있다. 리더 교체 뒤 새 Primary를 자동 추적하는 기능과 WAL 연속성은 별도 관문이다.

OpenSQL은 [Barman 구성 모델과 역할 변경 콜백](https://docs.tibero.com/tmaxopensql/installation/configuration/barman)을 안내한다. 현재 백업 VM에는 공급사의 Barman Agent가 설치되어 있지 않고, DB 노드의 Patroni REST 관리 포트는 백업 VM에서 접근할 수 없다. 이 설계는 기존의 **GET `/metrics` 전용 게이트웨이**에서 역할을 읽고 Barman 모델을 전환한다. HTTP 관리 엔드포인트를 새로 노출하지 않는다.

```text
DB 노드 3대: Patroni → 각 노드의 GET /metrics 전용 게이트웨이
                           │  백업 VM 단일 출발지 → TCP 18008
                           ▼
백업 VM: 1분 주기 감시기 → 실행 중인 노드 2대 이상·Primary 1대 확인
                           │  조건 불충족이면 변경 중지
                           ▼
                 Barman active_model·conninfo·streaming_conninfo 조회
                           │  이미 일치하면 기존 수신 유지
                           ▼
                 config-switch·대상 확인 → 필요할 때만 receive-wal 정지 → cron
                           │
                           ▼
                 전환 대상 재조회 + streaming·receiver 상태 확인
```

## 안전 조건

1. 감시기는 세 개의 RFC1918 주소에 대한 고정 `GET /metrics`만 수행한다. 응답 크기와 시간을 제한하고 `patroni_primary`, `patroni_postgres_running`을 각각 한 개씩 읽는다. **실행 중인 노드가 2대 미만이거나 Primary가 0대·2대 이상이면 전환하지 않는다.**
2. Barman 설정은 [configuration model](https://docs.pgbarman.org/release/3.20.0/user_guide/configuration.html#configuration-models) 세 개로 미리 준비한다. 각 모델은 같은 `cluster`를 사용하고 `conninfo`와 `streaming_conninfo`의 대상만 해당 DB 노드로 바꾼다. 감시기는 `show-servers`의 `active_model`과 두 대상이 이미 일치하면 반복 전환하지 않는다.
3. 전환이 필요할 때 `config-switch`를 먼저 실행하고 두 연결 대상이 새 Primary인지 재조회한다. 모델 적용 자체가 실패하면 기존 수신기를 멈추지 않는다. 대상이 바뀐 경우에만 기존 `receive-wal`을 정지한 뒤 `barman cron`으로 새 대상의 수신을 시작한다. `barman check`의 PostgreSQL streaming·receive-wal running이 OK가 되지 않으면 실패로 남긴다. **WAL 위치를 건너뛰는 `receive-wal --reset`은 호출하지 않는다.**
4. 모든 Barman 명령 출력과 접속 문자열은 프로세스 안에서만 처리한다. systemd 로그에는 응답 노드 수·선택한 순번·전환 여부·실패 종류만 남긴다. 파일 잠금으로 겹친 타이머 실행을 막는다.

## 적용 전 관문

| 관문 | 확인할 내용 | 실패 시 동작 |
| --- | --- | --- |
| 네트워크 | 백업 VM 한 대에서 DB 3노드의 18008만 접근, 일반 Patroni 관리 포트는 계속 차단 | 감시기 미활성화 |
| 모델 | 실제 Barman 서버 이름·cluster와 모델 3개 일치, 두 접속 문자열이 각 노드로 연결 | `config-switch` 실행 금지 |
| 복제 슬롯 | Patroni가 기존 Barman 물리 슬롯을 리더 교체 시에도 유지·생성하는지, 세 노드의 WAL 보존 한도를 확인 | 리더 변경 시험 금지 |
| 실행 권한 | `barman` 계정이 세 DB 노드에 SQL·복제 연결 가능 | 감시기 미활성화 |
| 연속성 | 전환 전후 WAL 파일·timeline·수신 재개와 격리 복원을 대조 | RPO 보장 주장 금지 |

Patroni의 [영구 복제 슬롯 설명](https://patroni.readthedocs.io/en/latest/dynamic_configuration.html#slots)은 물리 슬롯을 리더 교체 후에도 관리할 수 있지만, 슬롯을 둔 채 수신이 멈추면 WAL이 누적될 수 있다고 경고한다. 슬롯 정책과 디스크 경보를 확인하기 전에는 구성 모델과 감시기를 활성화하지 않는다. `create_slot=auto`만 켜서 새 리더의 최신 위치에 슬롯을 만드는 것으로 과거 WAL의 연속성을 입증할 수 없다.

2026-10-16 적용 과정에서 Standby의 복제 HBA 누락을 확인하고 백업 VM의 내부 주소로 제한한 규칙을 추가했다. 이후 SQL·복제 프로토콜 접속은 모두 3/3 성공했다. 세 노드의 슬롯 WAL 보존 한도를 4GB로 맞추고 기존 Barman 물리 슬롯을 Patroni DCS의 **최상위 `slots`**에 등록했으며 실제 슬롯 생성 3/3을 확인했다. 첫 시도에서는 잘못된 `postgresql.slots` 위치에 등록해 API 응답만 성공하고 Standby 슬롯이 생성되지 않았으므로, [실패 기록](../test-results/barman-wal-follow-20261016/permanent-slot-r1.md)을 남겼다.

## 설치·검증 순서

1. `scripts/opensql/barman-wal-follow.example.json`의 노드 주소·Barman 서버 이름·모델 이름을 **백업 VM의 비공개 설정 파일**에만 채운다. 주소와 접속 계정은 저장소에 기록하지 않는다.
2. Barman 모델 세 개를 `/etc/barman.d/`에 준비하고 `barman list-server`, `barman show-servers`로 구성을 점검한다. Patroni의 슬롯 정책과 세 노드 접속을 먼저 확인한다.
3. 감시기·service·timer를 백업 VM에 설치한다. `--dry-run`으로 현재 Primary와 모델 차이만 본 뒤 한 번 수동 실행한다. `barman check`에서 WAL 수신 상태를 확인한 후 timer를 켠다.
4. 통제된 역할 변경 시험에서 **이전 WAL 마지막 위치 → 새 리더 timeline → 첫 수신 WAL → 복원 가능 시점**을 별도 실행 기록으로 남긴다. 앱 쓰기 원장과 비교하기 전에는 RPO 숫자를 붙이지 않는다.
5. 롤백은 timer 중지 → Barman 수신 정지 → 이전에 확인한 모델로 전환 → `barman cron`·`barman check` 순서다. 새 timeline이 생긴 뒤 과거 Primary로 억지 전환하지 않고 현재 Patroni 리더를 다시 확인한다.

현재 [실행 기록](../test-results/barman-wal-follow-20261016/README.md)에서는 모델 3개·1분 timer를 설치하고 계획된 리더 전환을 1회 실행했다. 리더는 순번 2→1로, timeline은 19→20으로 바뀌었다. 전환 직후 Barman 슬롯·수신 검사는 실패했으나, 다음 관측에서 감시기의 모델 정렬과 세 검사 통과를 확인했다. timeline 20의 history와 완성된 WAL 세그먼트를 Barman에서 읽었다.

이어 [격리 PITR 시험](../test-results/barman-wal-follow-20261016/pitr-timeline20.md)에서 timeline 18 기준 백업과 timeline 19·20 WAL로 timeline 20의 지정 시각을 복원했다. 원래 Standby의 데이터 경로를 건드리지 않는 별도 경로와 localhost 포트를 사용했다. 목표 시각 전후 시험 기록 A·B를 기준으로 A 1건·B 0건, 복구 모드 종료를 확인했고 격리 복원본은 timeline 21로 승격됐다. 이는 **시험 기록을 이용한 DB 시점 판정**이며 사용자 성공 응답 전체나 GCS 객체의 보존을 검증한 결과는 아니다.

첫 격리 시험에서는 안전 장치가 `docker start`만 실행해, 기본 명령이 `sleep infinity`인 컨테이너의 Patroni·PostgreSQL을 다시 시작하지 못했다. 실제 bootstrap을 포함한 원복 경로로 수정하고 후속 실행마다 재합류를 확인했다. 격리 OpenSQL에는 실행 중인 DB와 동일한 라이선스 경로가 필요했으며, 경로·값은 원격 프로세스 안에서만 사용하고 실행 기록에는 남기지 않았다. **복원 시험은 먼저 원복 타이머의 실제 bootstrap 경로를 검증한 다음 진행**해야 한다.
