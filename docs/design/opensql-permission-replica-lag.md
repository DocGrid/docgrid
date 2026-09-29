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
- 두 standby 호스트에 시험 JVM·SSH 연결과 독립된 자동 원복 타이머, 원본
  Patroni YAML 백업을 준비한다. 단독 standby에서 자동 원복을 확인한 뒤에만
  두 노드에 지연을 적용한다. `finally`와 수동 원복은 추가 방어선이다.
- 지연 동안 수신·재생 LSN 차이와 디스크 여유를 감시한다. 미리 정한 상한,
  역할 변경, 타이머 오작동, 관측 누락 중 하나라도 발생하면 즉시 재개한다.
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
지연된 standby가 failover 후보가 되는 위험을 최소화하도록 짧은 관측 구간을
두며, 이 시험 중 primary 장애를 의도적으로 주입하지 않는다. `patronictl
pause`나 Patroni 중지로 HA 제어 자체를 우회하지 않는다.

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

1. 두 시험 사용자를 primary에 만들고 양쪽 standby에서도 ADMIN이 보이는지 확인한다.
2. 독립 자동 원복 타이머가 무장된 것을 확인한 후 양쪽 standby의 Patroni 로컬
   설정에 apply delay를 추가하고 Patroni를 reload한다. 양쪽의 실제
   `pg_settings` 값이 동일하며 타이머가 예정되어 있을 때만 다음 단계로 간다.
3. 관리자 HTTP API로 대상 ADMIN을 회수한다. 회수 응답과 세 노드의 역할 행,
   Redis 키 부재를 확인한다. 회수 뒤 읽은 primary LSN은 정확한 commit LSN이
   아닌 참조 시점으로만 사용한다.
4. 대상 사용자로 `/admin/workers`를 한 번 호출하고 상태 코드, 역할 SQL의
   노드별 증가량, Redis의 ADMIN 여부와 PTTL을 기록한다.
5. 즉시 양쪽 standby의 원본 Patroni 설정을 복원한다. 회수가 반영된 이후의 첫 403 시각을
   1초 간격으로 관측해 복제 지연 구간과 캐시 잔류 구간을 구분한다.
6. 성공·실패와 관계없이 설정 원복·복제·단일 primary를 확인하고 이번 실행의
   사용자·역할·Redis 키만 정리한다. 정상 원복 뒤 자동 원복 예약을 해제한다.

## 증거와 완료 조건

기존 `ha_evidence.py`의 run ID와 요청·fault 이벤트를 재사용한다. 추가
비식별 증거에는 코드 커밋, 설정 해시, UTC 시각, 노드 별칭, 실제 역할 상태,
실효 지연값, receive/replay LSN, SQL 호출 수 차이, HTTP 상태, Redis ADMIN 여부와
PTTL, 자동 원복 발동 여부를 기록한다. 비밀번호·JWT·응답 본문·주소·실제
사용자 정보는 공개 파일에 넣지 않는다.

결과는 `STALE_ADMIN_ALLOWED`, `DENIED`, `INVALID`로 나눈다. `DENIED`는
이번 환경에서 재현되지 않았다는 뜻이며 향후 설정 변경까지 안전하다는 증명은
아니다. `pg_stat_statements`는 노드별 누적 통계이므로 다른 요청과 분리할 수
없으면 `INVALID`로 처리한다. 이 PR의 성공은 취약점 재현 자체가 아니라 네
시나리오의 유효한 판정, 원본 증거, 정상 원복 및 한계 문서화다.
