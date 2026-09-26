# OpenSQL 3노드 OpenProxy 라우팅·세션 계약 실측 (2026-09-26)

## 목적과 판정 범위

두 OpenProxy를 거친 실제 SQL의 primary/standby 역할, 트랜잭션 풀에서의 pgJDBC named prepared statement, 시간대 경계, HA 관련 설치·동적 설정을 **운영 데이터를 바꾸지 않고** 확인했다. 이 문서는 장애 전환, 물리 standby별 부하 분산, Hikari/JPA 전체 경로의 무중단성을 합격 처리하지 않는다. 기존 Hikari/JPA 쓰기 검증은 [별도 결과](gimin-opensql-openproxy-hikari-troubleshooting-20260924.md)를 참조한다.

공개 증거는 논리 별칭 `node1~3`, `proxy-a/b`만 사용한다. 프로젝트 ID, IP, 관리자·앱 암호, 라이선스 파일은 포함하지 않는다. 수집 당시 `node1`이 leader, `node2/3`이 streaming replica였고, 프록시는 각각 `node2/3`에서 실행됐다. 세 컨테이너 모두 Rocky Linux 9.7 `x86_64`였다.

실행 코드 기준은 `origin/develop@98324caeb176`에 이 PR의 테스트·수집기 변경을 더한 상태다. 최종 전체 계약 시험은 2026-09-26 07:37 UTC에 4개 테스트/실패 0건으로 완료됐다. 머신 고유명이 들어 있는 원본 XML은 공개하지 않았다.

## 재현 절차

1. 설치된 각 컨테이너에서 [`capture_ha_contract.py`](../../scripts/opensql/capture_ha_contract.py)의 `collect --node node1|node2|node3`를 실행한다. 이 동작은 설치 TOML의 허용된 키, `patronictl show-config/list`, 버전과 OS만 출력한다. 원본 TOML 전체를 복사하지 않는다.
2. 각 VM 호스트에서 같은 스크립트의 `runtime --node ...`를 실행해 Docker 재시작 정책, 호스트 bootstrap unit, 살아 있는 OpenProxy 프로세스의 부모 PID를 별도로 기록한다. 컨테이너 안의 systemd 템플릿을 실제 감독자로 해석하지 않는다.
3. `node2/3` 컨테이너에서 `admin --node ...`를 실행한다. 수집기는 관리자 암호를 컨테이너 안에서만 읽고 서비스 포트로 `SHOW CONFIG`, `SHOW STATS`, `SHOW SERVERS`를 읽는다. 허용된 설정값과 역할별 숫자만 출력한다.
4. 세 `collect` 결과를 `assemble node1.json node2.json node3.json`으로 대조한다. OS·Patroni 동적 설정·두 프록시의 설치 설정/버전 불일치와 민감 문자열은 실패시킨다. 공개 스냅샷의 canonical SHA-256은 `b9bb7dbd246c53265fe23cadd507e8224b92fc45bda16c836f340c92cac9841c`이다.
5. A/B JDBC URL과 최소 권한 앱 계정 환경 변수를 주입한 뒤 `./backend/gradlew -p backend openSqlOpenProxyContractTest`를 실행한다. 일반 `test`에서는 외부 클러스터 태그를 제외한다. URL의 `connectTimeout=5&socketTimeout=15`는 **이번 수동 시험에만** 적용했고, 앱 운영 URL 변경은 아니다. SSH 터널은 이 시험의 접근 경로일 뿐 장애·성능 측정 경로로 사용하지 않는다.

수집기의 비밀값 차단·중복 설정 감지·스냅샷 일치성은 `python3 -m unittest scripts.opensql.test_capture_ha_contract -v`로 6개 시험/실패 0건을 확인했다.

공개 원본: [node1](opensql-contract-evidence/node1.json), [node2](opensql-contract-evidence/node2.json), [node3](opensql-contract-evidence/node3.json), [proxy-a 관리값](opensql-contract-evidence/proxy-a-admin.json), [proxy-b 관리값](opensql-contract-evidence/proxy-b-admin.json), [캐시 카운터 전후](opensql-contract-evidence/prepared-cache-delta.json). 원본 Gradle XML은 로컬 빌드 산출물이라 커밋하지 않았다.

## 설치·실행 계약

| 항목 | 실측 결과 | 해석 |
| --- | --- | --- |
| 제품 | OpenProxy 1.1.3 revision 723, Patroni 4.0.5, etcd 3.6.5, PostgreSQL client 17.8 | 두 프록시의 설치 버전 동일 |
| Patroni 동적 설정 | `ttl=30`, `loop_wait=10`, `retry_timeout=10`, `maximum_lag_on_failover=1048576`, `failsafe_mode=true` | `primary_start_timeout`, 동기 복제 모드는 동적 설정에 명시되지 않음. 미설정을 `0`으로 해석하지 않는다. |
| DocGrid 풀 | `pool_mode=transaction`, `default_role=primary`, query parser와 read/write splitting 활성, `primary_reads_enabled=false`, standby 선택 모드 `Random` | A/B 설치 파일과 관리 콘솔의 공통 항목이 일치한다. 물리 standby별 요청 비율을 이번 SQL 시험으로 판정하지는 않는다. |
| 캐시 설정 | 설치 TOML 일반 섹션 `prepared_statements_cache_size=1000`; `SHOW CONFIG`의 **풀 항목**은 `0` | 두 표기가 상충한다. `0`을 “실제 캐시 완전 비활성”이라고 단정하지 않는다. 아래 실측과 공급사 확인이 필요하다. |
| 실행 감독 | 컨테이너 정책 `unless-stopped`; 호스트 bootstrap unit `inactive`; 살아 있는 OpenProxy의 부모는 컨테이너 PID 1 | 패키지에 동봉된 `Restart=always`, `RestartSec=1` systemd **예시 파일**이 현재 프로세스를 감독한다는 증거가 없다. 프로세스 종료 시 자동 재기동을 가정하지 않는다. |

관리 설정의 `admin_port=6433`은 컨테이너 localhost에서 열려 있지 않았고, 실제 관리 `SHOW` 명령은 서비스 포트 `6432`로 성공했다. 이 차이는 설치 빌드의 관리 포트 의미를 추가 확인할 항목이다.

## SQL 라우팅 결과

| SQL/세션 조건 | proxy-a | proxy-b | 근거·주의 |
| --- | --- | --- | --- |
| 자동 커밋 `SELECT pg_is_in_recovery()` | standby | standby | 각 SQL이 돌려준 DB 역할. 어느 물리 standby였는지는 판정하지 않음. |
| 명시적 트랜잭션의 `SELECT → UPDATE` | primary | primary | `UPDATE ... WHERE id=-1`은 0행이고 트랜잭션을 rollback함. |
| JDBC read-only 트랜잭션의 SELECT | standby | standby | **`@Transactional(readOnly=true)`만으로 primary 일관성을 보장할 수 없다는 반례.** Spring 자체의 실제 라우팅은 후속 앱 통합 시험에서 재확인해야 함. |
| `FOR UPDATE SKIP LOCKED` 빈 결과 조회 | primary 성공 | primary 성공 | Embedding Job·Outbox·RAG 응답 테이블 각각 0행 잠금 조회. standby는 `FOR UPDATE`를 실행할 수 없으므로 성공이 primary 라우팅의 증거다. 실제 Worker claim 트랜잭션 전체를 시험한 것은 아님. |

따라서 권한 회수 후 최신 역할을 반드시 읽어야 하는 경로에 read-only 어노테이션만 붙이는 수정은 금지한다. 전용 primary 경로 또는 명시적인 라우팅 정책을 별도로 설계·검증해야 한다. OpenProxy가 잘못 동작했다는 결론이 아니라, 애플리케이션의 일관성 경계를 제품 라우팅과 맞춰야 한다는 결론이다.

## pgJDBC prepared statement와 시간대

각 프록시에서 `prepareThreshold=5`와 `1`로 같은 JDBC PreparedStatement를 15회, 트랜잭션을 매번 커밋하며 반복했다. 네 조합 모두 결과값이 정확했고, 드라이버의 server-prepare 플래그가 켜졌으며, 마지막 트랜잭션의 DB `pg_prepared_statements`도 0보다 컸다. `SHOW SERVERS`에는 캐시 hit/miss가 관측됐다. 별도 반복 시험 전후의 합산 카운터는 양 프록시 모두 **hit +224, miss +80, eviction 0**이었다. 이는 캐시 경로가 사용된다는 강한 정황이지만, 카운터는 프록시 전체의 누적값이며 다른 트래픽을 완전히 격리하지 않았다.

한계가 중요하다. 반복 중 각 JDBC 연결에서 관측한 DB backend는 **1개**였다. 따라서 **서로 다른 backend로 옮겨 간 named statement의 재준비/캐시 적중은 아직 검증하지 않았다.** `SHOW CONFIG` 풀 값 `0`과 일반 TOML `1000`의 의미도 이번 시험만으로 확정하지 않는다. 공급사에 우선 확인하고, 이후 backend 교체를 강제한 회귀 시험을 추가한다.

양 프록시에서 `SHOW TimeZone=Asia/Seoul`이었고, 한국 시간 자정 직후의 `timestamp` 벽시계값과 `timestamptz` 순간값이 왕복 보존됐다. 트랜잭션 안의 `SET LOCAL TIME ZONE 'Pacific/Honolulu'`는 rollback 뒤 다음 요청에 남지 않았다. 전체 애플리케이션의 날짜 직렬화나 모든 세션 설정을 보증하는 결과는 아니다.

## 다음 작업의 전제와 미검증 사항

- OpenProxy 프로세스 종료·지속 stop·패킷 DROP은 서로 다른 장애다. 현재 실제 supervisor 상태를 기준으로 각각 따로 주입한다.
- Patroni `failsafe_mode=true`이므로 etcd 정족수 상실 시 무조건 쓰기 정지를 기대하지 않는다. `primary_start_timeout`은 동적 설정에 없으므로 기본값·실제 failover 시간을 별도 검증한다.
- 읽기 권한·역할 조회를 primary에 고정할 설계는 이 PR에 포함하지 않는다. standby 읽기 분산이 존재한다는 사실만으로 권한 회수의 즉시성을 주장할 수 없다.
- 이번 실측은 라우팅·세션 계약의 기준선이다. 프록시 A/B 장애 전환, 리더 상실, Worker 멱등 복구, 실제 부하·p95/p99는 후속 시험이다.

참조: [OpenProxy 설정·관리 콘솔](https://docs.tibero.com/tmaxopensql.en/installation/configuration/openproxy), [OpenProxy 읽기/쓰기 라우팅과 캐시](https://docs.tibero.com/tmaxopensql.en/administration/openproxy/load-balancing), [pgJDBC server-side prepare](https://jdbc.postgresql.org/documentation/server-prepare/), [Patroni 동적 설정](https://patroni.readthedocs.io/en/latest/dynamic_configuration.html).
