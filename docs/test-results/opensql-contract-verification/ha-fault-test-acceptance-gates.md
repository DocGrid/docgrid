# 후속 HA 장애 시험의 사전 판정 기준

## 이 문서의 성격

이 항목의 “완료”는 **장애 주입 전에 통과·경고·실패·중단 기준을 정의했다**는 뜻이다. 이번 계약 확인에서는 OpenProxy 프로세스 종료, DB 프로세스 종료, 리더 VM 상실, etcd 정족수 상실을 **실행하지 않았다**. 따라서 여기에 RTO/RPO 실측값은 없다. 아래 시간은 DocGrid의 시험 목표이며 OpenSQL 제품 보장 수치가 아니다.

## 실제로 실행한 확인 명령·위치

기준을 정하기 전에 개발자 컴퓨터의 저장소 루트에서 `bash scripts/opensql/capture_live_ha_contract.sh`를 실행해 Patroni/etcd·OpenProxy 설정과 실행 감독을 수집했다. 이 문서를 작성할 때는 같은 worktree에서 다음 읽기 전용 명령으로 공개 [manifest](../opensql-contract-evidence/contract-manifest.json)의 판정 입력을 다시 확인했다.

```bash
jq '{patroni: [.snapshot.nodes[] | {node, failsafe_mode: .patroni_dynamic.failsafe_mode, ttl: .patroni_dynamic.ttl, primary_start_timeout: .patroni_dynamic.primary_start_timeout}], proxies: [.snapshot.admins[] | {proxy, pool_mode: .effective_config["pools.docgrid.pool_mode"], connect_timeout: .effective_config.connect_timeout, shutdown_timeout: .effective_config.shutdown_timeout}]}' docs/test-results/opensql-contract-evidence/contract-manifest.json
```

결과는 세 노드 모두 `failsafe_mode=true`, `ttl=30`, `primary_start_timeout=null`; 프록시 A/B 모두 `pool_mode=Transaction`, `connect_timeout=10000`, `shutdown_timeout=60000`이었다. `primary_start_timeout=null`은 동적 설정에 명시되지 않았다는 뜻이지 값 `0`이 아니다. 설치 예시 systemd unit의 재시작 옵션과 실제 실행 감독은 [별도 설정 결과](ha-settings-and-runtime-supervision.md)에 구분했다.

이 단계에서 **실행하지 않은 명령**도 명확히 적는다: `kill -9`, `systemctl stop`, 방화벽 `DROP`, VM stop/delete, `etcdctl member remove`, `pg_wal_replay_pause()` 등. 아래 표는 그 명령들을 실행한 결과가 아니라 안전하게 시험하기 위한 사전 약속이다.

## 측정 규칙

- 부하 발생기와 앱은 DB 3노드 밖의 **GCP 내부**에서 돌린다. SSH 터널을 통해 장애 시간이나 p95/p99를 재지 않는다.
- 부하 강도는 정상 상태에서 찾은 최대 **안정** 처리량의 60~70%로 고정하고, 장애 전후에 같은 요청 유형·데이터셋을 쓴다. 아직 그 최대 안정 처리량은 측정하지 않았다.
- 요청마다 `request_id`를 DB 외부 원장과 최종 DB 양쪽에 남긴다. 성공 HTTP 응답을 받은 ID 중 최종 DB에 없는 수를 관측 RPO로 보고한다. 앱 HTTP→Hikari→OpenProxy를 거치게 할 probe 경로·테이블은 **후속 구현 대상**이고, 이번 PR에 없다.
- RTO는 장애 주입 시각부터가 아니라 **마지막 정상 성공 이후 30초 연속 안정 회복 구간의 시작까지**로 정의한다. 실패·결과 불명·재시도·중복 건수를 별도 계수한다.
- 요청 표본에서 p95/p99를 계산한다. 장애 반복 5개의 RTO에서 p95를 만들어 성능 지표처럼 쓰지 않고 개별 값·범위를 공개한다.
- 장애 직전 Patroni/etcd 각 3/3 정상, A/B 프록시 정상, 원복 절차·원장 보존을 확인하지 못하면 주입하지 않는다. 종료 후 원래 토폴로지·복제 건강·설정으로 돌아왔는지도 판정한다.

## 장애별 통과·중단 기준

| 후속 시험 | 장애 유형을 구분할 이유 | DocGrid 통과 목표 | 경고·실패·즉시 중단 |
| --- | --- | --- | --- |
| OpenProxy A/B | 프로세스 kill은 재시작할 수 있고, 지속 stop은 다른 시험이며, 패킷 DROP은 응답 없이 매달릴 수 있다. | A 중단과 B 중단을 각각 반복. 새 연결이 살아 있는 프록시로 도달하고 RTO ≤30초, 성공 응답 누락·중복 0건. | 30~60초 경고, >60초 실패. 상대 프록시에도 못 붙거나 성공 응답이 사라지면 즉시 중단. |
| PostgreSQL 프로세스 종료 | Patroni가 **같은 노드 재시작**을 선택할 수 있으므로 “새 리더 선출”과 구분한다. | 실제 복구 유형을 기록하고 RTO ≤60초, 성공 응답 누락·중복 0건. | 이중 writable primary 또는 원장 대조 불가 시 즉시 중단. |
| 리더 VM 상실 | PostgreSQL 프로세스 종료와 달리 노드 자체가 빠져 TTL 이후 failover가 필요할 수 있다. | 새 단일 리더와 라우팅 회복 RTO ≤120초, 성공 응답 후 누락 ID 수 공개. | RTO 초과 실패. 비동기 복제로 RPO>0이면 DocGrid의 무손실 목표 실패; 이중 리더 즉시 중단. |
| Worker 인덱싱 중 리더 상실 | lease·Outbox·임베딩 저장의 중간 상태를 최종 결과와 구분해야 한다. | 최종 완료 문서·청크·임베딩·Outbox 누락/중복 0건. | 최종 상태 불일치·재처리 불가 실패. 시험 전용 pause 지점 구현 전에는 ‘임베딩 저장 중’ 주입을 주장하지 않는다. |
| etcd 멤버 장애 | 1대 상실(2/3)과 2대 상실(1/3)은 다르며 현재 `failsafe_mode=true`다. | 단일 writable primary 유지 여부와 복구 후 정상 복제를 기록. | 2대 상실 시 무조건 쓰기 정지를 기대하지 않는다. 이중 리더·복구 불능·사전 스냅샷 부재는 즉시 중단. |

## 해석과 남은 일

이 기준은 [설치 설정](../opensql-contract-evidence/contract-manifest.json)과 [라우팅 JUnit](../opensql-contract-evidence/junit-summary.json)을 바탕으로 썼다. 하지만 “30초 안에 전환된다”, “성공 응답 손실이 0이다”는 아직 **가설이자 목표**다. HA 실험을 시작하려면 GCP 내부 부하 발생기, HTTP probe API/DB 원장, 장애 주입·복구 자동화, 사전 스냅샷을 구현하고, 각 장애 유형별 원본 요청/DB 대조값을 남겨야 한다. 특히 비동기 복제에서는 성공 응답한 쓰기가 리더 상실 후 사라질 가능성을 시험 전부터 인정한다.
