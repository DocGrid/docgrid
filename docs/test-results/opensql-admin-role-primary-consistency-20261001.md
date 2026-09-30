# OpenProxy 경유 관리자 권한 회수 정합성 검증

## 목적과 변경 경계

기존 재현 시험에서는 두 standby의 WAL 적용을 잠시 늦춘 뒤 ADMIN 역할을 회수했을 때, OpenProxy 경유 `/admin/**` 요청이 뒤처진 역할을 읽어 200으로 통과했다. 이 변경은 **HTTP 관리자 인가**에 한해 매 요청을 명시적 read-write 트랜잭션의 현재 primary에서 검증한다. 같은 연결에서 `pg_is_in_recovery() = false`를 확인하지 못하거나 DB 조회가 실패하면 캐시된 ADMIN을 사용하지 않고 503을 반환한다. 일반 API의 기존 Redis 캐시 경로와 WebSocket 세션 인가는 이번 변경 범위가 아니다.

일반 역할 캐시에는 조회 전 epoch를 기억하고 Lua CAS로 저장하는 장치를 추가했다. 역할 변경 후에는 epoch 증가와 캐시 삭제를 하나의 Redis 스크립트로 실행한다. 이 장치는 **DB 조회와 캐시 저장 사이의 무효화 경쟁**을 막는다. 그러나 일반 경로가 무효화 이후 새 epoch를 읽고 지연된 standby에서 옛 권한을 읽는 경우까지 해결하지 않는다. 따라서 “모든 권한 조회가 최신”이라는 결론은 내리지 않는다. 관리자 HTTP 경로가 캐시와 standby를 우회하는 것이 보안 경계다.

## 검증 환경과 절차

- 실행: 2026-10-01 KST. 로컬의 격리된 앱 두 인스턴스에서 loopback SSH 터널로 GCP 3노드 OpenSQL에 접속했다. 한 인스턴스는 OpenProxy 두 주소, 다른 인스턴스는 primary 직접 경로를 사용했다. 임시 Redis는 별도 loopback 포트의 자동 삭제 컨테이너였다.
- 코드: `fix/admin-role-revocation-consistency`의 `ac6bd86`까지. 실제 실행 JAR SHA-256은 `8317e9c5e1d17aa21e9fde941c379412cc39d6749c80b0d65eb73afd6928fe57`이다. 비밀이 없는 live preflight SHA-256은 `ba05ec8fd820ea2201c2448d88b482b461a3aa576423ed7e627fa63aa7c4564e`다.
- 비밀번호는 승인된 SSH 스트림에서 테스트 프로세스 메모리로만 전달했다. 공개 문서·커밋·원장에는 값과 서버 주소를 넣지 않았다. 시험 전용 사용자 4개는 보호 타이머 아래 생성·삭제했다.
- 사전 관문: 실제 GCP 계정·프로젝트, 1 primary/2 streaming standby, 설치된 보호 helper 해시, 두 standby의 정상 복제·승격 자격, ADMIN 기본 200, 역할 SQL의 primary 도착을 확인했다.
- 장애 주입: 두 standby 각각에 독립 복구 타이머를 먼저 예약하고 `nofailover=true`와 120초 apply delay를 적용했다. 해당 구간의 primary 장애 시 자동 failover가 제한될 수 있으므로 이 상태를 지속 운영 구성으로 해석하면 안 된다. 회수 API가 200을 반환한 뒤 OpenProxy 요청과 직접 primary 대조 요청을 실행했다.
- 정리: 지연을 즉시 해제하고 정상 복제를 확인한 뒤 시험 역할·사용자를 삭제하고 보호 타이머를 취소했다. 별도 read-only 재확인에서 시험 계정 0건, node2·3 모두 `streaming=true`, `armed=false`, `delay=0`, `nofailover=false`, WAL backlog 0이었다. 임시 Redis와 SSH 터널도 종료했다.

동일 방식의 재실행 진입점은 `scripts/opensql/run_permission_replica_lag_local.py --credentials-stdin --apply-delay --expect-primary-admin`이다. `--credentials-stdin`은 승인된 SSH 표준출력에만 연결하고, 다른 환경값은 비공개 `.env`와 `OPENSQL_*` 변수로 제공한다. 이 명령은 실제 standby 정책을 일시 변경하므로 독립 복구 타이머와 운영 승인 없이 실행하지 않는다.

## 관측값과 해석

원장 run ID `2075ca4865f1`에서 16요청의 결과는 2xx 13건, 명시적 실패 3건, 결과 불명 0건이었다. 장애 구간은 UTC `2026-09-30T17:56:57.911Z`부터 `17:58:02.784Z`까지였다. 아래 SQL 수는 해당 단계 전후 `pg_stat_statements`의 역할 조회 증가량이며, node1은 primary, node2·3은 당시 standby였다.

| 단계 | DB 역할 상태 | OpenProxy 관리자 HTTP | 역할 SQL 증가량 node1/node2/node3 | Redis ADMIN 캐시 | 판정 |
| --- | --- | ---: | ---: | --- | --- |
| R: 정상 라우팅 6회 | 세 노드 모두 최신 | 6회 모두 200 | 6 / 0 / 0 | 캐시 미사용 | 관리자 권한 조회가 primary에 고정됨 |
| M: ADMIN 회수 직후 | primary에는 회수 반영, 두 standby에는 이전 ADMIN 유지 | **403** | **1 / 0 / 0** | 없음 | 뒤처진 standby가 있어도 권한 우회 없음 |
| C2: 직접 primary 대조 | M과 동일 | 직접 경로 **403** | 1 / 0 / 0 | 없음 | 새 권한 기준 대조와 일치 |
| C1: 복제 복구 후 회수 | 세 노드 모두 회수 반영 | **403** | 1 / 0 / 0 | 없음 | 정상 상태에서도 같은 정책 유지 |

M 단계에서는 두 standby 모두 `armed=true`, `nofailover=true`, `delay=120000ms`였고, 각각 primary에는 없는 ADMIN 역할이 남아 있었다. 그런데 M 요청은 primary에서 역할 SQL이 1회 실행되고 403이었다. 따라서 이 403을 단순한 인증 실패나 standby 미지연으로 오인하지 않는다. 실행 분류는 `PRIMARY_AUTH_ENFORCED`였다.

`scripts/opensql/ha_evidence.py verify --run-dir <private-run-dir>` 결과 `complete=true`, 열린 장애 구간 0, 결과 불명 0이었다. 원본 `manifest.json`, `events.jsonl`, `requests.csv`, `permission-scenarios.json`, 앱 로그와 live preflight는 서버 식별자·내부 경로가 포함될 수 있어 비공개 실행 폴더에만 보관하며 Git에는 올리지 않는다.

## 자동 회귀 검증과 한계

- 관리자 Security filter 전체 경로: 정상 ADMIN 200, 회수 후 403, primary 조회 불가 시 503, 일반 API 기존 동작을 테스트했다.
- 역할 캐시: Redis epoch 변화 시 이전 DB 조회 결과가 캐시에 다시 쓰이지 않도록 단위 테스트와 격리 Redis Lua 실행으로 확인했다.
- `python3 -m unittest discover -s scripts/opensql -p 'test_permission_replica_lag.py'`: 6개 통과. 관련 Java 단위·MVC 테스트도 Gradle `test`에서 통과했다.
- `OpenSqlProxyJpaIntegrationTest`는 별도 외부 클러스터 태그로 일반 Gradle `test`에서 제외된다. 이번 실제 경로 증거는 위 HTTP 실행과 노드별 SQL 계측이다.
- 503 경로는 로컬 보안 체인 테스트로 검증했다. 실제 primary 장애를 일으키는 503 실험은 이번에 하지 않았다.
- WebSocket CONNECT·SUBSCRIBE·기존 구독의 역할 수명, 일반 API의 standby 지연 후 캐시 최신성, Redis 무효화 실패나 DB failover 중 이미 성공 응답된 회수의 RPO는 이 실험으로 증명되지 않는다. 별도 검증이 필요하다.
