# Patroni·etcd·OpenProxy·실행 감독 설정 확인

## 검증 질문

리더 장애·프록시 장애 시험의 결과를 해석하기 전에, 실제 설치 설정과 실행 감독자가 무엇인지 알아야 한다. 특히 `PostgreSQL 프로세스 종료 = 반드시 새 리더 선출`, `OpenProxy kill = 계속 꺼짐`, `etcd 2대 장애 = 무조건 쓰기 중단`처럼 단정하면 안 된다. 이 문서는 **장애 주입 전 설정 기준선**을 기록한다. 장애를 직접 발생시킨 결과는 아니다.

## 실행 위치·명령·이유

개발자 컴퓨터의 저장소 루트에서 실제 수집 진입점 `bash scripts/opensql/capture_live_ha_contract.sh`를 실행했다. 승인 계정·프로젝트·존·SSH 키를 환경 변수로 주었고, 값은 공개 JSON이나 문서에 넣지 않았다. 이 스크립트가 `node1/2/3`마다 다음 두 호출을 분리해서 수행했다.

```bash
# Rocky 컨테이너: 설치 설정, Patroni/etcd 상태, 바이너리 기본값
sudo docker exec --user opensql -i docgrid-nodeN python3 - collect --node nodeN

# 동일 VM 호스트: Docker 정책, host systemd unit, 실행 중 프로세스
python3 - runtime --node nodeN
```

위 두 줄은 개발자 컴퓨터에서 SSH를 통해 **원격 실행된 명령의 본문**이다. `python3 -`의 표준 입력으로 [`capture_ha_contract.py`](../../../scripts/opensql/capture_ha_contract.py)를 보냈다. 원격 호스트에 수집 스크립트를 영구 설치하지 않았다. 수집기 내부의 읽기 전용 호출은 아래와 같다.

| 실행 장소 | 수집기 내부 호출·조회 | 왜 필요한가 |
| --- | --- | --- |
| 각 Rocky 컨테이너 | `patronictl -c <설치된 patroni.yml> show-config` 및 `list --format json` | 동적 HA 정책과 당시 3멤버 역할을 분리해서 확인한다. 전체 설정 파일은 공개하지 않는다. |
| 각 Rocky 컨테이너 | `etcdctl --endpoints=http://127.0.0.1:2379 member list --write-out=json` | 부트스트랩 파일에 적힌 3멤버가 아니라 **실행 중인** 멤버가 3개인지 확인한다. 주소는 결과에서 제거한다. |
| 각 Rocky 컨테이너 | 설치된 `etcd --help` + 실행 etcd 프로세스의 시간 관련 인자·환경 변수 검사 | `heartbeat-interval`, `election-timeout`이 명시 값인지 설치 바이너리 기본값인지 판별한다. 프로세스 인자 전체는 공개하지 않는다. |
| OpenProxy 설치 컨테이너 | `openproxy.toml`의 허용 키와 동봉 `openproxy.service`의 `Restart/RestartSec`만 읽기 | 풀·라우팅·캐시 값과 **예시 systemd 파일**의 내용을 기록한다. 암호·주소는 읽기 출력에 포함하지 않는다. |
| 각 GCP VM 호스트 | `sudo docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' docgrid-nodeN` | 컨테이너 재시작 정책을 확인한다. |
| 각 GCP VM 호스트 | `systemctl show docgrid-opensql@docgrid-nodeN.service -p ActiveState -p Restart -p RestartUSec` | 호스트의 실제 bootstrap unit 상태를 확인한다. 설치 예시 파일과 혼동하지 않는다. |
| 각 GCP VM 호스트에서 컨테이너 내부 조회 | `sudo docker exec docgrid-nodeN ps -eo ppid=,stat=,comm=` | 살아 있는 OpenProxy 개수와 부모가 컨테이너 PID 1인지 확인한다. |

## 코드의 판별 방식

[`etcd_timing_inputs()`](../../../scripts/opensql/capture_ha_contract.py)은 실행 etcd 프로세스가 정확히 하나인지 확인하고, 시간 관련 CLI 인자와 환경 변수를 먼저 검사한다. 별도 `--config-file`이 있으면 우선순위가 불명확해 수집을 실패시킨다. 이번에는 override가 없어 해당 설치 바이너리 `--help` 기본값을 `source=installed binary default`로 저장했다. 이는 **실행 입력의 추론**이며 선출 시간을 재서 검증한 값은 아니다.

[`runtime()`](../../../scripts/opensql/capture_ha_contract.py)은 Docker 정책과 host unit, 살아 있는 프록시 프로세스를 별도로 수집한다. 따라서 패키지에 동봉된 `Restart=always`를 실제 supervisor 정책으로 잘못 보고하지 않는다.

## 관측 결과

| 항목 | 세 노드 또는 A/B의 관측값 | 결과 해석 |
| --- | --- | --- |
| Patroni | `ttl=30`, `loop_wait=10`, `retry_timeout=10`, `maximum_lag_on_failover=1048576`, `failsafe_mode=true` | 실제 동적 설정 허용 항목이 세 노드에서 같았다. `primary_start_timeout`, `primary_stop_timeout`, `synchronous_mode`, `check_timeline`은 동적 설정에 **명시되지 않아 `null`**이며 `0`으로 해석하지 않는다. |
| etcd 멤버 | 구성 이름·실행 멤버 모두 `node1/2/3`, 정족수 `2` | `initial_cluster_state=new`는 최초 부트스트랩 입력값이지 현재 새 클러스터를 생성한다는 뜻이 아니다. |
| etcd 시간 입력 | heartbeat `100ms`, election timeout `1000ms`; 둘 다 `installed binary default` | 명시적 override가 없음을 확인했다. 실제 장애 때 선출 지연은 별도 측정해야 한다. |
| OpenProxy 풀 | `pool_mode=transaction`, `default_role=primary`, parser·읽기/쓰기 분리 활성, `primary_reads_enabled=false`, `Random` | 연결이 프록시를 통과할 때의 기대 라우팅 조건이다. `Random`만으로 물리 standby 분산 비율을 입증하지 않는다. |
| 캐시 표기 | 일반 TOML `prepared_statements_cache_size=1000`, 관리자 풀 조회값 `0` | 두 범위의 값이 달라 **유효 우선순위 미확정**. `0=완전 비활성`이라고 단정하지 않는다. |
| Docker / host unit | Docker `unless-stopped`; host bootstrap unit `inactive`, `Restart=on-failure`, `RestartUSec=15s` | 동봉 OpenProxy service 예시의 `Restart=always`, `RestartSec=1`이 현재 프록시를 감독한다는 증거가 없다. |
| 살아 있는 OpenProxy | node1 `0`, node2 `1`, node3 `1`; node2/3의 부모는 컨테이너 PID 1 | 두 프록시가 실행 중이라는 증거다. 프로세스 kill 뒤 누가 얼마 만에 재시작할지는 아직 모른다. |

원본: [node1](../opensql-contract-evidence/node1.json), [node2](../opensql-contract-evidence/node2.json), [node3](../opensql-contract-evidence/node3.json), [A 관리자 값](../opensql-contract-evidence/proxy-a-admin.json), [B 관리자 값](../opensql-contract-evidence/proxy-b-admin.json). 관리자 조회는 `SHOW CONFIG/STATS/SERVERS`를 서비스 포트에서 읽었으며 설정 변경 명령은 실행하지 않았다. `SHOW STATS`는 누적값이라 이번 수집만의 요청량으로 해석하지 않는다.

## 판정·미검증

설정 **기록** 기준은 완료됐다. 그러나 OpenProxy 프로세스 `kill`, `systemctl stop`, 네트워크 DROP, PostgreSQL 프로세스 종료, VM 상실, etcd 정족수 상실은 이 단계에서 실행하지 않았다. 각 장애의 재시작/선출/쓰기 가능 시간은 후속 실험으로만 판정한다. `failsafe_mode=true`에서 etcd 정족수 상실을 단순히 “즉시 쓰기 정지”로 예측하지 않는다.
