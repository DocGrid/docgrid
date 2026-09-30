# OpenSQL 권한 조회 라우팅 사전 시험과 Patroni 재생 제약

## 판정

2026-09-29 UTC, 기존 3노드 OpenSQL·OpenProxy 환경에서 실제 DocGrid HTTP
인가 경로의 **권한 SQL이 standby에 도착함**을 확인했다. Redis 키를 비우고
`GET /admin/workers`를 6회 호출했을 때 모두 200이었고, 노드별
`pg_stat_statements`의 해당 SQL 호출 수는 primary 0, standby A 5,
standby B 1 증가했다. 이것은 정상 복제 상태의 라우팅 증거일 뿐, 권한 회수
후 stale ADMIN 허용의 증거는 아니다.

두 standby의 WAL replay를 동시에 멈추려는 다음 관문은 통과하지 못했다.
각 standby에서 `pg_wal_replay_pause()`는 잠시 `paused`가 되었지만,
Patroni 4.0.5가 다음 HA 루프에서 직접 재개했다. 노드 로그에는 각각
08:15:37.302/08:15:38.291 `recovery has paused`, 08:15:40.503/08:15:40.504
`Resuming paused WAL replay for PostgreSQL 14+`가 남았다. **회수 API,
stale-role 판정, C1·C2 대조군은 실행하지 않았다.** 이 실행의 결과는
`INVALID`이며 보안 취약점이나 안전성을 주장할 수 없다.

## 실행 경로와 증거

공개 문서에는 계정·프로젝트 ID·IP·JDBC URL·비밀번호·JWT·사용자 이메일을
기록하지 않는다. 원본 개인 실행 원장과 두 앱 로그는 Git 밖의 권한 제한된
로컬 출력 디렉터리에 보관한다. 이 실행은 아직 커밋되지 않은 작업 트리에서
수행했으므로 특정 Git SHA로 완전 재현했다고 주장하지 않는다.

| 수행 위치 | 명령·동작 | 이유 | 결과 요약 |
| --- | --- | --- | --- |
| VM host, node1~3 | `sudo /usr/local/sbin/docgrid-permission-node-probe health docgrid-nodeN` | primary 1대·streaming standby 2대와 노드별 통계 확장을 확인 | node1 `f,t,2,0`; node2·3 `t,t,0,1` (순서: recovery, extension, sender 수, receiver 수) |
| VM host, node2·3 | `sudo /usr/local/sbin/docgrid-standby-replay-guard arm ...` 후 `status`·`cancel` | JVM·SSH와 독립된 자동 재개 예약 검증 | 양쪽 모두 예정 타이머를 확인했고 정상 상태에서 취소 가능 |
| VM host, node1 | `sudo /usr/local/sbin/docgrid-permission-fixture create ...` → `remove` → `status` | 실행별 격리 계정의 생성·정리 검증 | 시험 계정 4개 생성, 삭제 후 조회 행 0개 |
| 이 Mac | 프록시 경유 앱과 primary 직결 앱을 별도 JVM·Hikari 풀로 localhost에 기동 | HTTP 인가 경로를 통과하면서 C2 경로를 분리 | 두 앱 기동; 본 실행에서는 C2 요청 전 중단 |
| 이 Mac → 앱 | 매 요청 전 Redis `DEL auth:roles:{testUserId}` 후 `GET /admin/workers` 6회 | DB 조회가 실제 발생하게 강제 | HTTP 200 6회, Redis 역할 캐시 생성 확인 |
| VM host, node1~3 | `sudo /usr/local/sbin/docgrid-permission-node-probe role-stats docgrid-nodeN` 전후 비교 | 권한 SQL의 물리 도착 노드 판정 | primary +0, standby A +5, standby B +1 |
| VM host, node2·3 | 자동 재개 예약 후 `pg_wal_replay_pause()` 및 `status` | M 시나리오 선행 조건인 양쪽 `paused` 확인 | Patroni가 두 노드를 약 2~3초 안에 재개하여 관문 실패 |
| VM host, node1~3 | fixture `status`, standby guard `status` | 실패 후 원복 검증 | 시험 계정 행 0개; node2·3 모두 `streaming=true`, `replay=not paused`, `armed=false`, backlog 0B |

`pg_stat_statements`는 누적 통계다. 시험 계정의 Redis 키를 각 요청 직전에
비우고 백그라운드 Worker·Dispatcher를 끈 별도 앱을 사용했지만, 호출 수
증가만으로 단일 요청과 SQL을 1:1 대응시킬 수는 없다. 이 한계는 후속 실험에
남겨둔다.

## 원인과 다음 관문

PostgreSQL의 수동 WAL replay pause를 Patroni가 정상 HA 루프에서 해제한다.
Patroni 로그와 [공식 릴리스 노트](https://patroni.readthedocs.io/en/latest/releases.html)의
PostgreSQL 14 이상 동작 설명이 일치한다. 타이머는 연결이 끊겼을 때 복구하기
위한 안전장치였고, 이번 조기 재개의 원인은 타이머가 아니라 Patroni였다.

따라서 SQL pause를 계속 반복하거나 Patroni를 중지하는 방식으로 결과를
만들지 않는다. 이 사전 시험 시점에는 라우팅 기준선 R만 **검증**했고
권한 회수 후 stale-role M·C1·C2는 **미검증**이었다.

### 후속 실험 기록

같은 날 `ALTER SYSTEM SET recovery_min_apply_delay`를 node2 단독으로 시험했지만
Patroni가 약 1초 뒤 해당 recovery 파라미터를 제거해 실효값이 기본 0으로
돌아갔다. 이것 역시 타이머 원복이 아니라 Patroni의 설정 소유권 때문이었다.
이 시도에서 M·C1·C2는 실행하지 않았고 일회용 계정은 삭제했다.

이후 각 standby의 Patroni **로컬** `postgresql.recovery_conf`를 원본 백업과
독립 원복 타이머 아래에서 잠시 변경하는 방법을 단독 노드에서 검증했다.
그 방법으로 두 번의 유효한 HTTP 권한 시험을 마쳤으며 결과와 원복 증거는
[복제 지연 재현 결과](opensql-permission-replica-lag-20260929.md)에 별도로
기록했다. 이 문서의 `INVALID`는 첫 사전 시험에만 해당한다.
