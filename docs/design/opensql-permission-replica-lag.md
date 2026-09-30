# OpenProxy 권한 조회와 복제 지연 재현 설계

## 목적과 경계

관리자 역할 회수 API가 성공한 뒤 시작한 `/admin/**` 요청이 OpenProxy를 통해
뒤처진 standby의 옛 ADMIN 역할을 읽고 허용되는지 판정한다. 이 변경은 운영 인가
코드를 수정하지 않는다. Redis 캐시 부활 경쟁과 WebSocket 세션 권한은 별도 과제다.

`JwtAuthenticationFilter`는 JWT의 `userId`로 `RoleAuthorityService`를 호출한다.
Redis `auth:roles:{userId}`가 없으면 `UserRoleRepository.findRoleCodesByUserId`가
DB에서 역할을 읽고 30초간 캐시한다. `UserRoleCommandService.revokeRole`은 DB
커밋 뒤 역할 캐시를 지운다. 권한 SQL이 standby에 도착하는지는 추측하지 않고
실제 HTTP 경로와 노드별 통계로 판정한다.

## 사전 조건과 중단 기준

- 승인된 GCP 계정·프로젝트의 기존 세 VM만 사용한다. 실험용 앱은 기존 앱과
  별도 프로세스로 실행하고, Flyway·Worker·Dispatcher를 끈다.
- primary 1대와 streaming standby 2대를 확인한다. 이미 다른 복제 지연이나
  장애·부하 시험이 실행 중이면 시작하지 않는다.
- 역할 SQL의 standby 도착을 먼저 입증하지 못하면 지연을 적용하지 않는다.
- 이전 실행의 시험 ADMIN 계정이 0개인지 먼저 확인한다. 이번 실행의 네 계정은
  node1 VM 호스트에 독립 정리 타이머가 무장된 뒤에만 생성한다.
- 두 standby 호스트에 시험 JVM·SSH 연결과 독립된 자동 원복 타이머, 원본
  Patroni YAML의 root 전용 영속 백업을 준비한다. 단독 standby에서 원복을 확인한 뒤에만
  두 노드에 지연을 적용한다. `finally`와 수동 원복은 추가 방어선이다.
- 지연과 함께 각 standby의 로컬 `tags.nofailover: true`를 임시 적용한다.
  Patroni 로컬 REST와 클러스터 뷰 양쪽에서 승격 제외가 확인되기 전에는 권한을
  회수하지 않는다. 두 standby를 동시에 제외한 동안 primary가 상실되면 자동
  failover가 멈추므로, 이 실험은 승인된 짧은 구간에서만 실행한다.
- 적용 전 디스크 여유가 1 GiB 이상인지 검사하고, 관측 시점마다 수신·재생 LSN
  차이, 역할, 타이머 상태를 기록한다. 역할 변경, 타이머 오작동, 관측 누락 또는
  자동 원복 시한 접근 시 즉시 중단한다. 연속적인 backlog 감시는 구현하지 않았다.
- 자동 원복 장치가 아직 검증되지 않았다면 클러스터 실험은 실행하지 않는다.

### 2026-09-29 실제 사전 시험에서 발견한 제약

이 클러스터의 Patroni 4.0.5는 SQL로 일시정지한 standby WAL replay를 다음
HA 루프에서 직접 재개했다. 두 standby의 로그 모두 `Resuming paused WAL replay for
PostgreSQL 14+`를 기록했고, 실제 재생 중단은 약 2~3초만 유지됐다. 따라서 아래
`pg_wal_replay_pause()` 기반 M·C2 순서는 **현재 구성에서 실행 불가**다. 독립
systemd 자동 재개가 정상이라는 사실은 이 제약을 해결하지 않는다. 현재 러너는
라우팅 기준선 R을 측정한 뒤 기본적으로 M 진입을 거부한다.

직접 `ALTER SYSTEM`으로 준 `recovery_min_apply_delay` 역시 Patroni가 약
1초 뒤 제거했다. 이 값은 standby의 Patroni 로컬
`postgresql.recovery_conf`에서 관리하고, Patroni REST `POST /reload`를
사용해야 유지된다. 호스트 타이머가 원본 YAML을 정확히 되돌리고 reload한다.
지연된 standby는 적용 기간에 승격 후보에서 제외하고, 원복 뒤 승격 제외 태그가
양쪽 Patroni 뷰에서 사라졌는지 확인한다. 이 시험 중 primary 장애를 의도적으로
주입하지 않는다. `patronictl
pause`나 Patroni 중지로 HA 제어 자체를 우회하지 않는다.

VM 호스트의 `systemd-run` 타이머는 Mac 프로세스·SSH가 죽어도 작동하지만 VM
재부팅까지 지속되는 영구 타이머는 아니다. Patroni 원본 YAML은 영속 디렉터리에
보관하므로 재부팅 후에는 수동 `restore`가 가능하다. node1 자체가 사라지면
시험 계정 자동 삭제도 보장하지 못한다. 이 경우 시험을 `INVALID`로 처리하고
관리자가 복제·태그·계정을 확인해야 한다.

## 독립 시나리오

| 이름 | 조건 | 유효성 및 판정 |
| --- | --- | --- |
| R | 정상 복제, 앱 OpenProxy URL | 대상 사용자 Redis 키를 매 요청 전에 비우고 역할 SQL의 노드별 호출 수 차이를 기록한다. |
| M | 두 standby의 `recovery_min_apply_delay` 적용, 앱 OpenProxy URL | 회수 API 2xx, primary에서 ADMIN 없음, 양쪽 standby에 ADMIN 있음, 대상 Redis 키 없음이 선행 조건이다. 그다음 첫 `/admin/workers` 상태, 실제 라우팅, Redis 값·PTTL을 기록한다. |
| C1 | standby가 회수를 반영한 뒤, 앱 OpenProxy URL | 별도 시험 사용자와 비어 있는 Redis 키로 403인지 확인한다. |
| C2 | 두 standby는 지연, 앱은 검증된 primary 직결 URL | 별도 시험 사용자와 비어 있는 Redis 키로 403인지 확인한다. |

각 실행의 관리자와 대상 사용자 이메일은 서로 다른 무작위 run ID를 포함한다.
JWT는 요청을 받는 시험 앱과 같은 서명 설정으로 발급하며 토큰을 기록하지 않는다.
대상 사용자의 회수 전 `/admin/workers` 200을 확인한다. 관리자 API의 401이나
5xx는 권한 차단으로 해석하지 않는다.

## 본 실험 순서

1. node1 호스트의 독립 계정 정리 타이머를 무장한 뒤 네 시험 사용자를 만들고,
   양쪽 standby에서도 ADMIN이 보이는지 확인한다.
2. 각 standby의 독립 설정 원복 타이머를 확인한 후 Patroni 로컬 설정에 apply
   delay와 `nofailover`를 함께 추가하고 reload한다. 실효 `pg_settings`와
   로컬·클러스터 태그 모두 일치하며 타이머가 예정되어 있을 때만 다음 단계로 간다.
3. 관리자 HTTP API로 대상 ADMIN을 회수한다. 회수 응답과 세 노드의 역할 행,
   Redis 키 부재를 확인한다. 회수 뒤 읽은 primary LSN은 정확한 commit LSN이
   아닌 참조 시점으로만 사용한다.
4. 대상 사용자로 `/admin/workers`를 한 번 호출하고 상태 코드, 역할 SQL의
   노드별 증가량, Redis의 ADMIN 여부와 PTTL을 기록한다.
5. 즉시 양쪽 standby의 원본 Patroni 설정을 복원한다. 회수가 양쪽 standby에
   반영됐는지 확인한 뒤, 별도 사용자와 비어 있는 캐시로 C1의 403을 확인한다.
   현재 러너는 복구 중 첫 403 시각이나 캐시 잔류 기간을 측정하지 않는다.
6. 성공·실패와 관계없이 설정 원복·복제·승격 제외 해제를 확인한다. 이번 실행의
   Redis 키를 지운 뒤 사용자·역할을 삭제하고 계정 0개를 확인한다. 정상 원복 뒤
   각 자동 원복 예약을 해제한다. 삭제나 원복 확인이 실패하면 관측값은 보존하되
   전체 실행 결과와 종료 코드를 실패로 둔다.

## 증거와 완료 조건

기존 `ha_evidence.py`의 run ID를 시험 계정·결과 JSON·원장 manifest와 동일하게
사용한다. 실행 직전의 비밀 제거 스냅샷에는 설치된 호스트 스크립트·로컬 소스·앱
JAR 해시, JDBC URL의 해시, 세 노드의 health와 standby 상태, 이전 계약 해시를
담고 해당 바이트의 SHA-256을 원장에 기록한다. 추가 비식별 증거에는 UTC 시각,
노드 별칭, 실제 역할 상태,
실효 지연값, receive/replay LSN, SQL 호출 수 차이, HTTP 상태, Redis ADMIN 여부와
PTTL, 자동 원복 발동 여부를 기록한다. 비밀번호·JWT·응답 본문·주소·실제
사용자 정보는 공개 파일에 넣지 않는다.

결과는 `STALE_ADMIN_ALLOWED`, `DENIED`, `INVALID`로 나눈다. `DENIED`도
M의 역할 SQL이 standby에 도착했고 primary에는 도착하지 않았다는 증거와
두 대조군 403을 요구한다. 단순 HTTP 403만으로는 `INVALID`다. `DENIED`는
이번 환경에서 재현되지 않았다는 뜻이며 향후 설정 변경까지 안전하다는 증명은
아니다. `pg_stat_statements`는 노드별 누적 통계이므로 다른 요청과 분리할 수
없으면 `INVALID`로 처리한다. 이 시험의 성공은 취약점 재현 자체가 아니라 네
시나리오의 유효한 판정, 원본 증거, 정상 원복 및 한계 문서화다.
