# OpenSQL 3노드 OpenProxy 라우팅·세션 계약 실측 (2026-09-26)

## 목적과 판정 범위

두 OpenProxy를 거친 실제 SQL의 primary/standby 역할, SQL-level `PREPARE/EXECUTE`와 pgJDBC 프로토콜 준비문, 시간대 경계, HA 관련 설치·실행 설정을 **운영 데이터를 영구 변경하지 않고** 확인했다. 이 문서는 장애 전환, 물리 standby별 부하 분산, Hikari/JPA 전체 경로의 무중단성을 합격 처리하지 않는다. 기존 Hikari/JPA 쓰기 검증은 [별도 결과](gimin-opensql-openproxy-hikari-troubleshooting-20260924.md)를 참조한다.

공개 증거는 논리 별칭 `node1~3`, `proxy-a/b`만 사용한다. 프로젝트 ID, IP, 관리자·앱 암호, 라이선스 파일은 포함하지 않는다. 수집 당시 `node1`이 leader, `node2/3`이 streaming replica였고, 프록시는 각각 `node2/3`에서 실행됐다. 세 컨테이너 모두 Rocky Linux 9.7 `x86_64`였다.

실행 코드 기준은 `origin/develop@98324caeb176`에 이 PR의 테스트·수집기 커밋 `5ec0d53`을 더한 상태다. 최종 전체 계약 시험은 2026-09-26 12:46 UTC에 **5개 테스트/실패 0건**으로 완료됐다. 이후 Java import·주석만 정리하고 재컴파일했다. 머신 고유명이 들어 있는 Gradle XML은 커밋하지 않고, 테스트 이름·판정·허용된 관측값만 [JUnit 요약](opensql-contract-evidence/junit-summary.json)에 남겼다.

## 재현 절차

1. [`capture_live_ha_contract.sh`](../../scripts/opensql/capture_live_ha_contract.sh)에 승인된 GCP 계정·프로젝트, 기존 VM의 존과 SSH 키 경로를 환경 변수로 주고 실행한다. 스크립트는 실제 활성 계정·프로젝트가 지정한 대상과 다르면 시작 전에 종료한다. 각 Rocky 컨테이너에서는 [`capture_ha_contract.py`](../../scripts/opensql/capture_ha_contract.py)의 `collect`를 **etcd 실행 사용자**로 실행한다. OS·제품 버전, `patronictl show-config/list`, etcd 초기 멤버 파일·실행 멤버 목록·프로세스 입력·설치 바이너리 기본값, OpenProxy TOML의 허용된 키만 출력한다. 원본 설정 파일 전체는 복사하지 않는다.
2. 같은 자동 수집 과정에서 각 VM 호스트의 `runtime`을 별도로 실행해 Docker 재시작 정책, 호스트 bootstrap unit, 살아 있는 OpenProxy 프로세스의 부모 PID, VM 시간대를 기록한다. `merge`가 컨테이너·VM 결과의 노드 별칭을 검증하고 자동 병합한다. 기존 파일처럼 사람이 `runtime` 필드를 옮겨 적지 않는다.
3. `node2/3` 컨테이너의 `admin`은 관리자 암호를 **컨테이너 메모리에서만** 읽고 서비스 포트에서 `SHOW CONFIG`, `SHOW STATS`, `SHOW SERVERS`를 실행한다. 허용된 설정값과 역할별 누적 숫자만 출력한다. `proxy-a=node2`, `proxy-b=node3` 별칭도 수집기가 만든다.
4. `assemble`이 노드 3개와 관리자 결과 2개를 함께 대조하고 canonical JSON의 SHA-256을 만든다. OS·OpenSQL 제품 버전·Patroni 동적 설정·A/B 설치 및 관리 설정 불일치나 민감 문자열은 실패시킨다. 수집 시각은 **2026-09-26 12:57:23~12:58:14 UTC**, 공개 증거의 SHA-256은 `0a0b86eff71c4c24740a99ea0f334fca70e5c11e1a813c3a3bc646a825549794`이다. 해시는 공개 JSON의 무결성을 검사할 뿐 GCP가 발급한 원본 증명은 아니다.
5. A/B JDBC URL과 최소 권한 앱 계정 환경 변수를 주입해 `./backend/gradlew -p backend openSqlOpenProxyContractTest --rerun-tasks --offline`를 실행한다. `junit` 수집 모드가 XML의 호스트명을 버리고 5개 시험의 이름·판정·허용된 `CONTRACT_*` 출력만 보존한다. `connectTimeout=5&socketTimeout=15`는 **이번 수동 시험 URL에만** 붙였고 앱 운영 URL 변경은 아니다. SSH 터널은 접근 경로일 뿐 장애·성능 측정 경로로 사용하지 않는다.

수집기의 비밀값 차단·중복 감지·제품 버전 추출·etcd 시간 설정 우선순위·자동 병합·관리값 포함 해시·JUnit 비식별화는 `python3 -m unittest scripts.opensql.test_capture_ha_contract -v`로 **11개 시험/실패 0건**을 확인했다.

공개한 **비식별 수집 결과**: [node1](opensql-contract-evidence/node1.json), [node2](opensql-contract-evidence/node2.json), [node3](opensql-contract-evidence/node3.json), [proxy-a 관리값](opensql-contract-evidence/proxy-a-admin.json), [proxy-b 관리값](opensql-contract-evidence/proxy-b-admin.json), [통합 manifest](opensql-contract-evidence/contract-manifest.json), [JUnit 요약](opensql-contract-evidence/junit-summary.json), [이전 캐시 카운터 전후](opensql-contract-evidence/prepared-cache-delta.json). 마지막 캐시 카운터 파일은 **이전 실행의 관측값**이며 이번 통합 스냅샷·JUnit 실행과 같은 시점의 수치가 아니다. 원본 설정과 Gradle XML은 민감정보·머신 고유명 때문에 공개하지 않는다.

## 설치·실행 계약

| 항목 | 실측 결과 | 해석 |
| --- | --- | --- |
| 제품 | OpenSQL v3.17.8.7(세 노드), OpenProxy 1.1.3 revision 723(node2/3), Patroni 4.0.5, etcd 3.6.5, PostgreSQL 서버 실행 파일·클라이언트 17.8 | 세 노드의 설치 바이너리를 조회했다. 양쪽 프록시 JDBC 연결의 서버 메타데이터도 PostgreSQL 17.8이었다. |
| 앱 시험 의존성 | pgJDBC 42.7.11, HikariCP 6.3.3 | Gradle `dependencyInsight --configuration testRuntimeClasspath`로 각각의 해석된 버전을 조회했다. JDBC 실측의 드라이버 메타데이터도 42.7.11이다. 이 계약 시험은 `DriverManager`를 사용하므로 **Hikari를 실행해 시험한 것은 아니다.** |
| Patroni 동적 설정 | `ttl=30`, `loop_wait=10`, `retry_timeout=10`, `maximum_lag_on_failover=1048576`, `failsafe_mode=true` | `primary_start_timeout`, 동기 복제 모드는 동적 설정에 명시되지 않음. 미설정을 `0`으로 해석하지 않는다. |
| etcd 멤버·시간 설정 | 초기 구성 이름과 실행 멤버 모두 `node1/2/3`, 정족수 `2`; heartbeat `100ms`, election timeout `1000ms` | 실행 프로세스의 관련 환경·인자에 재정의가 없고 설치 바이너리 `--help` 기본값이 위 수치였다. 파일의 `initial_cluster_state=new`는 **부트스트랩 입력값**이지 현재 클러스터가 새로 생성 중이라는 뜻은 아니다. |
| DocGrid 풀 | `pool_mode=transaction`, `default_role=primary`, query parser와 read/write splitting 활성, `primary_reads_enabled=false`, standby 선택 모드 `Random` | A/B 설치 파일과 관리 콘솔의 공통 항목이 일치한다. 물리 standby별 요청 비율을 이번 SQL 시험으로 판정하지는 않는다. |
| 캐시 설정 | 설치 TOML 일반 섹션 `prepared_statements_cache_size=1000`; `SHOW CONFIG`의 **풀 항목**은 `0` | 두 표기가 상충한다. `0`을 “실제 캐시 완전 비활성”이라고 단정하지 않는다. 아래 실측과 공급사 확인이 필요하다. |
| 실행 감독·OS 시간대 | 컨테이너 정책 `unless-stopped`; 호스트 bootstrap unit `inactive`; 살아 있는 OpenProxy의 부모는 컨테이너 PID 1. 세 VM·컨테이너 시간대 표기는 `UTC+0000` | 동봉된 `Restart=always`, `RestartSec=1` systemd **예시 파일**이 현재 프로세스를 감독한다는 증거가 없다. OpenProxy 프로세스 종료 후 자동 재시작 방식·시간은 장애 시험에서 따로 잰다. |

관리 설정의 `admin_port=6433`은 컨테이너 localhost에서 열려 있지 않았고, 실제 관리 `SHOW` 명령은 서비스 포트 `6432`로 성공했다. 이 차이는 설치 빌드의 관리 포트 의미를 추가 확인할 항목이다.

## SQL 라우팅 결과

| SQL/세션 조건 | proxy-a | proxy-b | 근거·주의 |
| --- | --- | --- | --- |
| 자동 커밋 `SELECT pg_is_in_recovery()` | standby | standby | 각 SQL이 돌려준 DB 역할. 어느 물리 standby였는지는 판정하지 않음. |
| 명시적 트랜잭션의 `SELECT → UPDATE` | primary | primary | `UPDATE ... WHERE id=-1`은 0행이고 트랜잭션을 rollback함. |
| JDBC read-only 트랜잭션의 SELECT | standby | standby | **`@Transactional(readOnly=true)`만으로 primary 일관성을 보장할 수 없다는 반례.** Spring 자체의 실제 라우팅은 후속 앱 통합 시험에서 재확인해야 함. |
| `FOR UPDATE SKIP LOCKED` 빈 결과 조회 | primary 성공 | primary 성공 | Embedding Job·Outbox·RAG 응답 테이블 각각 0행 잠금 조회. standby는 `FOR UPDATE`를 실행할 수 없으므로 성공이 primary 라우팅의 증거다. 실제 Worker claim 트랜잭션 전체를 시험한 것은 아님. |

따라서 권한 회수 후 최신 역할을 반드시 읽어야 하는 경로에 read-only 어노테이션만 붙이는 수정은 금지한다. 전용 primary 경로 또는 명시적인 라우팅 정책을 별도로 설계·검증해야 한다. OpenProxy가 잘못 동작했다는 결론이 아니라, 애플리케이션의 일관성 경계를 제품 라우팅과 맞춰야 한다는 결론이다.

## SQL-level·pgJDBC prepared statement 구분

두 방식은 이름이 비슷하지만 다른 계약이다. SQL-level 방식은 애플리케이션이 서버 SQL 명령 `PREPARE 이름(integer) AS SELECT ...`, `EXECUTE 이름(41)`, `DEALLOCATE 이름`을 명시적으로 보낸다. 이번 시험은 A/B 모두 **같은 트랜잭션 안에서** 값 41을 확인하고 정리한 뒤 rollback했다. 이는 SQL-level 준비문이 여러 트랜잭션이나 서로 다른 DB backend에서도 유지된다는 증거는 아니다.

pgJDBC 프로토콜 방식은 JDBC `PreparedStatement`가 확장 쿼리 프로토콜을 사용한다. 각 프록시에서 `prepareThreshold=5`와 `1`로 같은 SQL을 15회, 트랜잭션을 매번 커밋하며 반복했다. 네 조합 모두 결과가 정확했고, 드라이버의 server-prepare 플래그와 마지막 backend의 `pg_prepared_statements > 0`을 **assert**했다. 최종 실측에서 네 조합의 서로 다른 backend 수는 각각 **1개**였다. 이전 [캐시 카운터 실험](opensql-contract-evidence/prepared-cache-delta.json)의 합산값은 각 프록시 hit +224, miss +80, eviction 0이었지만, 이번 최종 5개 JUnit 시험과 시점이 달라 수치를 합치지 않는다.

따라서 **backend 교체 시 named statement 재준비·캐시 적중은 아직 검증하지 않았다.** `SHOW CONFIG` 풀 값 `0`과 일반 TOML `1000`의 우선순위도 이번 시험만으로 확정하지 않는다. 공급사에 확인하고, 이후 backend 교체를 강제한 별도 회귀 시험이 필요하다.

## JVM·DB·OS 시간대와 날짜 경계

시험 JVM 기본 시간대는 `Asia/Seoul`, 양쪽 프록시를 거친 DB 세션의 `SHOW TimeZone`도 `Asia/Seoul`이었다. 반면 세 VM 호스트와 Rocky 컨테이너의 OS 시간대는 `UTC+0000`이었다. **시간대 설정이 전부 동일했던 것은 아니다.** 그 조건에서 한국 시간 `2026-09-27 00:00:01`의 `timestamp` 벽시계값과 `timestamptz`가 나타내는 순간이 A/B에서 각각 왕복 보존됐다. 트랜잭션 안의 `SET LOCAL TIME ZONE 'Pacific/Honolulu'`는 rollback 뒤 다음 요청에 남지 않았다. 전체 애플리케이션의 JSON 직렬화·모든 날짜 유형·다른 JVM 기본 시간대까지 보증하는 결과는 아니다.

## 이번 계약 시험의 완료 판정

아래의 ‘완료’는 **처음 정한 라우팅·세션 계약 확인 항목**에 대한 판정이지, 장애·부하 시험까지 합격했다는 뜻이 아니다.

| 성공 기준 | 판정과 증거 | 주장하지 않는 범위 |
| --- | --- | --- |
| 1. OpenSQL·OpenProxy·Patroni·etcd·PostgreSQL·pgJDBC·Hikari 버전 | 완료. 노드 JSON의 설치 바이너리·JDBC 서버/드라이버 메타데이터·Gradle 해석 결과를 기록했다. | Hikari를 이 시험에서 실행한 것은 아니다. |
| 2. Patroni·etcd·OpenProxy·systemd 관련 설정 | 완료. Patroni 동적 설정, etcd 멤버 및 실행 프로세스·바이너리 시간 입력, OpenProxy 설치·관리 설정, 실제 Docker/호스트 unit 상태를 구분해 기록했다. | `primary_start_timeout`처럼 명시되지 않은 Patroni 값과 이후 장애 동작은 추정하지 않는다. |
| 3. 프록시 A/B 계약 설정 일치 | 완료. `assemble`이 설치 TOML 허용 항목과 `SHOW CONFIG` 허용 항목을 각각 비교했다. | 모든 비밀값·주소를 포함한 원본 파일의 byte-for-byte 일치는 공개하지 않는다. |
| 4. A/B 명시적 쓰기 트랜잭션 → primary | 완료. 두 프록시에서 0행 UPDATE를 같은 트랜잭션에서 실행하고 rollback했다. | 실제 대량 쓰기 처리량은 측정하지 않았다. |
| 5. `prepareThreshold=5/1` 반복 | 완료. 양 프록시 × 두 임계값 × 15회, 결과·server prepare·마지막 backend 준비문 존재를 확인했다. | backend 교체 후의 재준비는 미검증이다. |
| 6. 자동 커밋·read-only·read-write 도착 역할 | 완료. JDBC 관측값은 각각 standby·standby·primary였다. | Spring `@Transactional` 전체 호출 경로와 물리 standby별 분포는 미검증이다. |
| 7. SQL-level과 프로토콜 준비문 구분 | 완료. `Statement`의 SQL-level `PREPARE/EXECUTE/DEALLOCATE`와 `PGStatement`의 프로토콜 server prepare를 별도 시험했다. | SQL-level 준비문의 트랜잭션 간·backend 간 유지 여부는 미검증이다. |
| 8. JVM·DB·OS 시간대와 경계 | 완료. JVM/DB `Asia/Seoul`, VM/컨테이너 `UTC+0000`을 기록하고 양 프록시의 자정 경계·`SET LOCAL` 복원을 확인했다. | 모든 날짜 직렬화 경로까지 검증하지 않았다. |
| 9. 비식별 canonical JSON·SHA-256 | 완료. 새 자동 수집·병합 경로가 노드 3개와 관리자 결과 2개를 검증·해시한다. | 해시는 수집 코드와 공개 파일의 일관성 지표이지 클라우드 제공자의 서명은 아니다. |
| 10. 후속 HA 판정 기준 | 완료. 바로 아래에 사전조건·통과·경고·즉시 중단 기준을 명시했다. | 장애 결과가 나왔다는 뜻은 아니다. |

## 후속 HA 시험의 사전 판정 기준

아래 시간은 **DocGrid의 시험 목표값**이며 OpenSQL의 제품 보장 수치가 아니다. 부하 발생기·앱은 DB 노드 밖의 GCP 내부에서 실행한다. 모든 요청의 `request_id`, HTTP 결과와 최종 DB 반영을 대조한 뒤 판정한다. RTO는 장애 시각이 아니라 **마지막 정상 성공부터 30초 연속 안정 구간의 시작까지**로 정의한다. RPO는 성공 응답을 받은 `request_id` 중 최종 DB에 없는 개수로 보고한다. 재시도 주체(HTTP 부하 발생기·앱·Worker)를 각각 구분한다.

| 시험 | 시작 전 필수 확인 | 통과 목표 | 경고·실패 또는 즉시 중단 |
| --- | --- | --- | --- |
| OpenProxy A/B 지속 중단 | A/B 설정 일치, 양쪽 경로에서 위 5개 계약 시험 통과, 정상 최대 안정 처리량 측정 후 60~70% 부하, 실제 앱 URL의 유한한 연결·소켓 제한시간 확인 | 한쪽 중단 후 새 연결이 다른 프록시로 도달, RTO 30초 이내, 성공 응답 누락·중복 0건, 오류·결과 불명 요청 수 공개 | RTO 30~60초 경고, 60초 초과 실패. 상대 프록시에도 접속하지 못하거나 성공 응답이 사라지면 즉시 중단한다. |
| PostgreSQL 프로세스 종료 | Patroni 역할·timeline·WAL LSN 기록, 복구 경로 확인 | 같은 노드 재시작인지 새 리더 선출인지 **실제 결과로** 분류, RTO 60초 이내, 성공 응답 누락·중복 0건 | 프로세스 종료를 곧바로 ‘새 리더 선출’로 세지 않는다. 이중 writable primary 또는 원장 대조 불가능 시 즉시 중단한다. |
| 리더 VM 상실 | 기존 리더 격리 방식·복구 절차 확정, Patroni/etcd 건강 상태 3/3, 요청 원장 DB 밖 보관 | 새 단일 리더 선출과 라우팅 회복 RTO 120초 이내, RPO와 모든 실패·재시도 건수 공개 | RTO 초과 실패. 비동기 복제에서 RPO>0이면 **DocGrid의 무손실 목표 실패**로 보고하고 제품이 무조건 0손실을 보장한다고 주장하지 않는다. 이중 리더면 즉시 중단한다. |
| Worker 인덱싱 중 리더 상실 | 시험 전용 pause 지점과 lease 만료·재처리 관측, 최종 DB 중복 키 점검 | 최종 완료 문서·청크·임베딩·Outbox 누락 및 중복 0건 | 최종 상태 불일치나 재처리 불능이면 실패. 미완료 작업을 지우고 재시도 성공으로 꾸미지 않는다. |
| etcd 멤버 장애 | 스냅샷·복구 절차 검증 후 별도 실행, `failsafe_mode=true`와 정족수 2/3 확인 | 1대 상실과 2대 상실을 구분하고 단일 writable primary·복구 후 정상 복제를 증명 | 2대 상실 시 무조건 쓰기 정지를 기대하지 않는다. 이중 리더, DCS 복구 불능, 스냅샷 부재 시 즉시 중단한다. |

공통으로 요청 실패율·p95/p99는 **요청 표본**에서 계산하고, 5회 장애 반복의 RTO는 개별 값과 범위를 제시한다. 반복 5개의 p95를 성능 지표처럼 사용하지 않는다. 장애 주입 전 Patroni 멤버가 3/3 정상·etcd 멤버가 3/3 정상·두 프록시 관리 상태가 정상이라는 사전조건을 충족하지 못하면 주입하지 않는다. 실패 후에는 원래 토폴로지·설정·복제 상태로 돌아왔는지도 별도로 판정한다.

## 다음 작업의 전제와 미검증 사항

- OpenProxy 프로세스 종료·지속 stop·패킷 DROP은 서로 다른 장애다. 현재 실제 supervisor 상태를 기준으로 각각 따로 주입한다.
- Patroni `failsafe_mode=true`이므로 etcd 정족수 상실 시 무조건 쓰기 정지를 기대하지 않는다. `primary_start_timeout`은 동적 설정에 없으므로 기본값·실제 failover 시간을 별도 검증한다.
- 읽기 권한·역할 조회를 primary에 고정할 설계는 이 PR에 포함하지 않는다. standby 읽기 분산이 존재한다는 사실만으로 권한 회수의 즉시성을 주장할 수 없다.
- 이번 실측은 라우팅·세션 계약의 기준선이다. 프록시 A/B 장애 전환, 리더 상실, Worker 멱등 복구, 실제 부하·p95/p99는 후속 시험이다.

참조: [OpenProxy 설정·관리 콘솔](https://docs.tibero.com/tmaxopensql.en/installation/configuration/openproxy), [OpenProxy 읽기/쓰기 라우팅과 캐시](https://docs.tibero.com/tmaxopensql.en/administration/openproxy/load-balancing), [pgJDBC server-side prepare](https://jdbc.postgresql.org/documentation/server-prepare/), [Patroni 동적 설정](https://patroni.readthedocs.io/en/latest/dynamic_configuration.html).
