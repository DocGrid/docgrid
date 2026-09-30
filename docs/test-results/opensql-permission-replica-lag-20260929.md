# OpenProxy 권한 조회의 복제 지연 재현

## 결론

2026-09-29 UTC, 기존 OpenSQL 3노드에서 DocGrid의 실제 HTTP 관리자 인가 경로를
두 번 실행했다. 두 번 모두 **관리자가 ADMIN 역할을 회수한 뒤**, primary에는
ADMIN이 없고 두 standby에는 아직 ADMIN이 남아 있는 동안 OpenProxy 경유
`GET /admin/workers`가 **200**을 반환했다. 해당 요청의 역할 SQL은 standby에
도착했고 Redis에 옛 ADMIN이 다시 캐시됐다. 같은 시점 primary 직결 대조군은
**403**, 복제 정상화 후 OpenProxy 대조군도 **403**이었다.

이 결과는 OpenProxy의 오작동이라는 주장이 아니다. 읽기 분산이 가능한 경로에서
최신 역할 판정을 수행한 **애플리케이션의 일관성 경계 문제**를 재현한 것이다.
Redis의 조회·DB 조회·재저장 사이 경쟁과 WebSocket 장기 세션은 이번 시험의
원인이거나 해결책으로 판정하지 않았다. 수정은 별도 작업에서 해야 한다.

## 범위·실행 기준

- OpenSQL `v3.17.8.7`, PostgreSQL 17.8, Patroni 4.0.5의 기존 VM 3대.
  제품·설정 기준 해시:
  `0a0b86eff71c4c24740a99ea0f334fca70e5c11e1a813c3a3bc646a825549794`.
  앱 JAR의 backend 코드 기준 커밋은 `2d4d56619280`이다.
- 앱 두 인스턴스와 Redis는 **시험을 실행한 Mac의 localhost**에서만 기동했다.
  하나는 OpenProxy A/B JDBC URL, 다른 하나는 primary 직결 URL을 사용했다.
  둘의 Hikari 풀이 분리되며 Flyway·Worker·Dispatcher는 껐다. SSH 터널은
  접속 수단이므로 이 시험은 GCP 내부 부하·장애 전환 성능 측정이 아니다.
- 시험 계정 네 개는 실행별 무작위 식별자를 가진 일회용 계정이다. 인증 토큰은
  두 시험 JVM과 동일한 비공개 키로 메모리에서만 서명했다. 공개 증거에는
  계정·프로젝트 ID·IP·JDBC URL·비밀번호·토큰·응답 본문을 넣지 않았다.
- 원본 요청 원장, 설정 manifest, 앱 로그와 상세 JSON은 Git 밖의 소유자 전용
  디렉터리에 남겼다. 아래 SHA-256은 두 번째 실행의 로컬 원본 확인용이다.
  `permission-scenarios.json`:
  `1f83f13a518e9b8ea6cc83e5afd43559a4a81d5535c5fc2e5b10628864d5c9a5`;
  `events.jsonl`:
  `0a028ff133533e4507f1e6d0034233d4588967b9d6871f3dedb5dbb76fbff9aa`.

## 왜 복제 지연을 이렇게 만들었나

첫 사전 시험의 `pg_wal_replay_pause()`는 Patroni가 다음 HA 루프에서 재개했다.
`ALTER SYSTEM SET recovery_min_apply_delay`도 Patroni가 약 1초 뒤 제거했다.
둘 다 유효한 지연 관문이 아니다.
[사전 시험 상세](opensql-permission-routing-preflight-20260929.md)에 실패 사례를
남겼다. Patroni가 관리하는 **각 standby의 로컬
`postgresql.recovery_conf.recovery_min_apply_delay`**에 한시적으로 120초를
넣고 Patroni `POST /reload`로 적용했다. PostgreSQL은 이 값으로 commit WAL
적용을 늦춘다. [PostgreSQL 설정 설명](https://www.postgresql.org/docs/17/runtime-config-replication.html),
[Patroni 로컬 설정·reload](https://patroni.readthedocs.io/en/latest/patronictl.html).

적용 전 각 VM 호스트에 원본 Patroni YAML의 root-only 백업과 독립된
`systemd-run` 240초 원복 타이머를 만들었다. 시험 프로세스의 `finally`도
원본 파일 복원·Patroni reload·실효값 0 확인·타이머 해제를 수행한다.
단독 node2에서는 30초 지연 중 새 역할이 node3에만 먼저 보였다가 node2에도
반영됐다. 단독 node3에서는 45초 타이머 서비스가 **종료된 뒤**
`delay=0,default`, streaming, backlog 0을 확인했다. 타이머 만료 직후
서비스가 아직 `active/running`일 때 지연이 보인 것은 원복 실패로 판정하지
않았다. VM 자체가 재부팅되는 경우 `/run` 백업과 transient timer가
보존되는지는 검증하지 않았으므로 이 가드는 운영용 자동화가 아니다.

## 실행 명령·관측

실제 계정·프로젝트·키·개인 `.env` 위치는 실행 시 비공개 환경변수로 전달한다.
명령은 시험용 코드의 위치를 보여주기 위한 축약형이며 비밀값을 포함하지 않는다.

| 실행 위치 | 명령 또는 코드 경로 | 이유 | 두 번째 실행 결과 요약 |
| --- | --- | --- | --- |
| 이 Mac | `python3 scripts/opensql/run_permission_replica_lag_local.py --env-file <private-env> --jar <built-jar> --output <private-output> --apply-delay` | OpenProxy 경유·primary 직결 앱을 독립 JVM으로 기동하고 한 번의 원장에 연결 | 두 JVM 기동, 종료 시 둘 다 정지. 총 결과 `STALE_ADMIN_ALLOWED` |
| node1~3 VM 호스트 | `sudo /usr/local/sbin/docgrid-permission-node-probe health <node>` | 한 primary·두 streaming standby와 `postgres` DB의 `pg_stat_statements` 확인 | node1 `f,t,2,0`; node2·3 `t,t,0,1` (recovery, extension, sender, receiver) |
| node1 VM 호스트 | `sudo /usr/local/sbin/docgrid-permission-fixture create <primary> <run-id>` | 실사용자와 분리된 ADMIN 시험 계정 네 개 생성 | 회수 전 각 사용자로 관리자 API 200; 양 standby에도 ADMIN 복제 확인 |
| 이 Mac → 프록시 앱 | Redis `DEL auth:roles:{id}` 후 `GET /admin/workers` 6회 | 캐시 미스를 강제해 역할 SQL의 실제 라우팅 확인 | R: HTTP 200×6, 역할 SQL 호출 증가 node1 +0, node2 +5, node3 +1 |
| node2·3 VM 호스트 | `standby-apply-delay-guard arm <node> <run-id> 240 120` → `apply ...` → `status ...` | 원본 백업과 독립 원복을 먼저 건 뒤 양 standby에 120초 apply delay 적용 | 두 노드 모두 `armed=true`, `delay=120000,configuration file`, streaming |
| 이 Mac → 프록시 앱 | 관리자 `DELETE /admin/users/{id}/roles/ADMIN` | 앱 트랜잭션·afterCommit 캐시 무효화를 실제 HTTP로 통과 | M·C2 회수 API 모두 200; primary ADMIN 없음, 양 standby ADMIN 있음, 대상 Redis 키 없음 |
| 이 Mac → 프록시 앱 | M 사용자 `GET /admin/workers`, 전후 `role-stats`, Redis `GET/PTTL` | 뒤처진 읽기의 보안 영향 확인 | HTTP **200**; 역할 SQL node3 +1·나머지 +0; Redis ADMIN 재저장, PTTL 24,900ms |
| 이 Mac → primary 직결 앱 | C2 사용자 `GET /admin/workers`, 전후 `role-stats` | 같은 지연 상태에서 최신 primary 판정과 대조 | HTTP **403**; 역할 SQL node1 +1·standby +0; Redis ADMIN 없음 |
| node1~3 VM 호스트 | `permission-node-probe lsn <node>`, 가드 `status` | 수신은 진행됐지만 standby 적용이 뒤처졌는지 확인 | 회수 뒤 primary/receive `0/5A45BE8`, 양 standby replay `0/5A45B28`; 관측 뒤 backlog 각 456B |
| node2·3 VM 호스트 | `standby-apply-delay-guard restore <node> <run-id> 240 120` → `cancel ...` | 원본 Patroni 설정·정상 복제 복원 | 두 노드 `delay=0,default`, streaming, backlog 0, 타이머 비활성 |
| 이 Mac → 프록시 앱 | 복제 반영 후 C1의 관리자 역할 회수·`GET /admin/workers` | 지연이 사라지면 같은 프록시 경로가 403인지 대조 | 회수 API 200, C1 요청 **403**, 세 노드 모두 ADMIN 없음 |
| node1 VM 호스트 | `permission-fixture remove/status <primary> <run-id>` | 이번 실행의 계정·역할만 삭제 | `status` 조회 행 0개. 임시 Redis·앱·터널도 종료 |

두 번째 실행에서는 09:30:09 UTC에 M 회수 200, 09:30:36에 M 요청 200,
09:30:41에 C2 요청 403이었다. 그 시점 양 standby의 WAL receive LSN은
`0/5A45CF0`, replay LSN은 `0/5A45B28`이었다. 최종 양 노드의 receive와
replay는 모두 `0/5A479E0`으로 같아졌고 원본 지연값 0을 유지했다. 이 LSN은
커밋의 정확한 LSN이 아니라 각 관측 시점의 위치다.

시험 종료 후에도 `postgres` DB에 앞선 사전 시험에서 만든
`pg_stat_statements` 확장과 VM의 root 전용 probe/fixture/guard 스크립트는
남아 있다. 일회용 계정·Redis 프로세스·시험 JVM·SSH 터널·Patroni 지연 설정은
남기지 않았다. 공개 스크립트의 최종 호스트 볼륨 자동 탐색 변경은 지연을
재적용하지 않고 두 standby의 read-only `status`로 확인했다.

## 두 실행의 교차 확인

| 항목 | 첫 유효 실행 | LSN 수집을 보강한 재실행 |
| --- | --- | --- |
| 정상 상태 역할 SQL 도착 | primary 0 / standby 2·4 | primary 0 / standby 5·1 |
| 권한 회수 후 M | 200, standby node2 +1, Redis ADMIN, PTTL 24,586ms | 200, standby node3 +1, Redis ADMIN, PTTL 24,900ms |
| 같은 지연 중 C2(primary 직결) | 403, primary +1 | 403, primary +1 |
| 복제 복원 뒤 C1(OpenProxy) | 403 | 403 |
| 지연 중 receive-replay 차이 | 두 standby 각 192B | 두 standby 각 456B |
| 종료 상태 | 양 standby 지연 0·streaming·backlog 0, 시험 계정 0 | 동일; 최종 receive/replay LSN 일치 |

LSN 수집을 처음 추가한 실행은 사전 검사에서 `docgrid` DB에도
`pg_stat_statements` 확장이 있어야 한다고 잘못 요구해 중단됐다. 실제 통계
조회는 확장을 설치한 `postgres` DB에서 다른 DB의 `dbid`로 수행한다.
검사만 수정했고, 이 중단된 실행은 fixture 생성·지연 적용 전에 끝났으므로
위의 유효 실행 횟수에 포함하지 않는다.

## 판정의 한계와 다음 조치

`pg_stat_statements`는 누적 통계라 단일 SQL과 단일 HTTP 요청을 직접 연결하는
추적 ID는 아니다. 다만 시험 JVM 외의 Worker·Dispatcher를 끄고 캐시를 매번
비웠으며, M/C2 직전·직후 카운터를 비교했고 DB 계정별 통계를 사용했다.
이 인과관계와 두 대조군은 이번 환경에서 **복제 지연 중 stale ADMIN 허용**을
지지한다. 모든 부하·모든 라우팅·장애 상황에서 같은 빈도라는 주장은 아니다.

이 시험은 HTTP 권한 수정이 아니다. 안전한 읽기 분산 경계는 권한 판정을
primary로 보내는 것과, Redis cache resurrection 경쟁을 별도로 막는 것을
함께 검토해야 한다. WebSocket 기존 구독, 실제 OpenProxy 프로세스 장애,
Patroni failover, 앱 VM의 GCP 내부 부하 시험은 여기서 검증하지 않았다.

이후 독립 계정 정리, standby 승격 제외, 엄격한 판정 및 실행 출처 연결을
보강해 두 standby에서 재검증했다.
[안전장치 보강 후 실행 결과](opensql-permission-replica-lag-safety-20261001.md)를
참고한다. 위의 2026-09-29 관측값과 당시 `/run` 백업 설명은 역사적 결과로 유지한다.
