# OpenProxy 권한 복제 지연 시험의 안전장치·증거 보강 결과

## 결론과 범위

2026-10-01 KST, 기존 OpenSQL 3노드에서 두 standby에 **동시에** 임시
`recovery_min_apply_delay=120s`와 `tags.nofailover: true`를 적용해 실제
DocGrid HTTP 권한 경로를 재검증했다. ADMIN 회수 후 OpenProxy 경유 요청은
**200**이었고, 역할 SQL은 지연된 standby에 도착했으며 Redis에 옛 ADMIN이
재저장됐다. 같은 시점 primary 직결 대조군과 복제 정상화 후 OpenProxy 대조군은
모두 **403**이었다. 이 문서는 [기존 재현 결과](opensql-permission-replica-lag-20260929.md)에
시험 안전성·판정·출처 검증을 더한 후속 기록이다. **인가 결함 자체를 고친
결과는 아니다.**

동시 적용 중 두 standby는 승격 후보에서 제외됐다. 따라서 그 짧은 구간에
primary까지 상실했다면 자동 failover가 불가능했을 수 있다. 이 위험을 승인받은
기존 시험 클러스터에서만 수행했고, primary 장애는 주입하지 않았다.
[Patroni의 `nofailover` 설정](https://patroni.readthedocs.io/en/latest/yaml_configuration.html)은
해당 멤버가 리더 경쟁에 참여하지 않도록 한다.

## 네 가지 보강과 검증

아래 명령의 `<run-id>`·`<node>`·`<private-...>`는 실제 값을 공개하지 않는
자리표시자다. 호스트 스크립트는 기존 VM의 root 전용 경로에 설치했다.
계정·프로젝트 ID·IP·JDBC URL·암호·JWT는 Git에 기록하지 않았다.

| 보강 | 실행 위치와 명령·코드 | 왜 실행했나 | 결과 요약과 해석 |
| --- | --- | --- | --- |
| 시험 ADMIN 계정 독립 정리 | node1 VM 호스트: `sudo /usr/local/sbin/docgrid-permission-fixture arm docgrid-node1 <run-id> 300` → `create` → `systemctl start docgrid-fixture-reset-<run-id>.service` → `guard-status` → `cancel` | 클라이언트 JVM·SSH가 비정상 종료돼도 VM 호스트의 별도 타이머가 이번 실행의 네 계정을 지우도록 한다. 실제 DB에 계정을 만든 뒤 서비스의 정리 동작을 연습했다. | 무장 확인 후에만 계정이 생성됐다. 정리 서비스 실행 후 `armed=true fixture_count=0`, 취소 후 `armed=false fixture_count=0`; 이전 실행 계정 검사도 0. **300초를 기다린 자동 발화 자체는 시험하지 않았고**, VM 재부팅·node1 상실에는 transient timer가 지속되지 않는다. |
| 지연 standby 승격 방지 | node2·3 VM 호스트 각각: `standby-apply-delay-guard arm <node> <run-id> 90 15` → `apply` → `status` → `restore` → `cancel` | 지연된 복제본을 새 primary로 뽑지 않도록 Patroni 로컬 설정의 apply delay와 `nofailover`를 함께 적용·원복한다. 원본 YAML은 root-only 영속 백업에 둔다. | 두 호스트 모두 적용 중 `streaming=true`, `delay=15000,configuration file`, `nofailover=true`가 로컬·클러스터 Patroni 뷰에서 확인됐다. 원복 뒤 `delay=0,default`, `nofailover=false`, `nofailover_cleared=true`, backlog 0. 그 뒤 본시험에서도 두 standby에 동일한 관문을 통과했다. |
| 판정·정리 실패 처리 | 로컬: `python3 -m unittest scripts.opensql.test_permission_replica_lag scripts.opensql.test_standby_apply_delay_guard scripts.opensql.test_ha_evidence` 및 실제 `permission_replica_lag.py` 실행 | 단순 403을 안전한 라우팅으로 오판하지 않고, standby SQL 도착·두 대조군·원복 상태를 모두 확인한다. cleanup 실패는 관측값과 별개로 전체 실행을 무효화한다. | 해당 단위 테스트 **14개** 통과. 본시험에서 M의 역할 SQL은 primary +0, node2 +1, node3 +0; C2는 primary +1, standby +0. C1·C2 모두 403. 최종 원복 경고가 없어서 결과 `STALE_ADMIN_ALLOWED`를 유지했다. cleanup 실패를 실제 클러스터에 주입한 시험은 아니다. |
| 동일 실행의 출처 연결 | 로컬: `run_permission_replica_lag_local.py --env-file <private-env> --jar <built-jar> --redis-port 16379 --output <private-output> --apply-delay`; VM: 각 helper의 `sha256sum`; 종료 후 `ha_evidence.py verify --run-dir <private-run-dir>` | 소스·설치 스크립트·실행 JAR·라이브 사전 상태가 서로 다른 시점의 것이면 결과를 혼동할 수 있다. 하나의 run ID와 해시로 원장을 묶는다. | 실행 ID `53688fbe7475`가 manifest·시나리오 JSON·시험 계정에 공통으로 쓰였다. 실행 코드 커밋 `5fba43e10901eb72f1b13b57f7cc7fb704eced19`; live preflight SHA-256 `84955dc776a71391f99c359f1e1d6e1d4e798044a1eec94dd014ba3cb020cab5`; JAR SHA-256 `a885e4740e9eeb9615452a84a3b07979a76410f2d3644c4e61075161613cac00`. 설치 helper와 체크아웃 소스의 해시가 일치했고 원장 검증은 `complete=true`였다. |

실행 JAR은 기존 DocGrid 코드의 빌드 산출물이다. 이 후속 변경은 서버의
인가 로직을 수정하지 않으며, 두 시험 JVM은 로컬 loopback에서만 실행했다.
Redis는 기존 인스턴스와 격리한 임시 컨테이너의 `127.0.0.1:16379`를 사용했다.
SSH 터널을 사용한 기능·정합성 시험이지 GCP 내부 부하 또는 장애 전환 성능
시험이 아니다.

## 최종 실제 실행의 관측값

최초 안전장치 재현은 코드 커밋 전의 예비 실행이었고, 아래 표는 **코드 커밋 뒤**
재실행한 `53688fbe7475`만을 기준으로 한다. UTC 실행 구간은
`2026-09-30T16:28:32.947Z`부터 `16:32:02.963Z`까지다. 원본 원장은
Git 밖의 소유자 전용 디렉터리에 보존했다.

| 관측 지점 | 실제 결과 | 해석 |
| --- | --- | --- |
| 시작 전 | primary 1대, streaming standby 2대. 두 standby 모두 지연 0, `nofailover=false`, backlog 0 | 이전 실험 설정 없이 시작했다. |
| R 정상 라우팅 | 6회 cache miss 중 역할 SQL 증가: primary +0, node2 +3, node3 +3 | 실제 앱의 OpenProxy 경로에서 권한 조회가 standby에 도착했다. |
| 두 standby 지연 구간 | 각각 `delay=120000,configuration file`, `nofailover=true`, `armed=true`, backlog 192B | 재생 지연과 승격 제외가 동시에 유효했다. 타이머 무장도 관측됐다. |
| M 회수 뒤 요청 | primary ADMIN 없음·양 standby ADMIN 있음. OpenProxy `GET /admin/workers` **200**; 역할 SQL primary +0, node2 +1, node3 +0; Redis ADMIN 재저장, PTTL 24,515ms | 뒤처진 권한을 읽은 앱이 관리 요청을 허용했다. 보안 정합성 결함은 미해결이다. |
| C2 지연 중 primary 직결 | **403**; 역할 SQL primary +1, standby +0 | 같은 DB 변경을 최신 primary에서 읽으면 거부된다. |
| C1 복제 복구 후 OpenProxy | **403**; 양 standby에서도 ADMIN 없음 | 복제 정상화 뒤 새 사용자의 권한 판단은 거부됐다. 첫 403 도달 시간은 측정하지 않았다. |
| 종료·원장 | 양 standby streaming, 지연 0, `nofailover=false`, backlog 0, timer 비활성. 시험 계정 0; `ha_evidence verify` complete true, 요청 16건 중 14건 2xx·2건 예상된 403·결과 불명 0 | 판정은 `STALE_ADMIN_ALLOWED`이고 cleanup 경고는 없다. `403` 2건은 대조군의 정상적인 거부다. |

비공개 원본 파일 확인용 SHA-256: `permission-scenarios.json` =
`72fc51c833e037ff49dcd83a241983498c24fb71b8d74ca8e6b05c5f77a2c85c`,
`events.jsonl` =
`42e93ed529be86154b046e833fa3cf9616fe620d85044f00034e8315efb4f453`.
라이브 preflight에는 당시 노드 health·standby 상태·이전 계약 증거 해시·앱 JAR·
로컬 소스 및 설치 helper 해시·JDBC URL **해시**를 넣었다. 이는 전 제품 설정을
다시 수집한 스냅샷은 아니므로 그 수준의 재현성까지 주장하지 않는다.

## 원복 확인과 남은 한계

시험 후 별도 조회로 양 standby의 지연 0·`nofailover` 해제·streaming·backlog
0을 다시 확인했다. node1의 이전 실행 시험 계정도 0이었다. 임시 Redis, 두
시험 JVM, SSH 터널과 로컬 임시 자격증명 사본은 종료·삭제했다. 기존 VM의
root 전용 helper 및 그 설치 전 백업은 남겨 두었다. 원래 DB 자격증명은 변경하지
않았다.

- `systemd-run` transient timer와 node1 계정 정리는 VM 재부팅·node1 상실까지
  보장하지 않는다. 영속 백업을 통한 수동 원복 경로는 있지만, 이 고장 유형은
  이번에 실제로 주입하지 않았다.
- cleanup 실패·이중 standby 중 primary 상실을 실제로 주입하지 않았다. 코드의
  `INVALID` 판정과 `nofailover` 관문은 단위 테스트·상태 조회로 검증했지만,
  장애 전체 조합의 안전성을 증명한 것은 아니다.
- `pg_stat_statements` 차이는 격리된 시험 구간의 누적 카운터 증거다. SQL
  단건의 분산 추적 ID나 높은 부하에서의 보안 위반율을 뜻하지 않는다.
- 이 결과는 OpenProxy 자체 결함, 권한 수정 완료, Redis cache resurrection
  해결, WebSocket 권한 회수, OpenProxy/primary 장애 시 HA 성능을 뜻하지 않는다.
