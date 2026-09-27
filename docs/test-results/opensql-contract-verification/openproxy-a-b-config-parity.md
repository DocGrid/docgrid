# OpenProxy A/B 설정 일치 검증

## 목적과 범위

이후 한쪽 OpenProxy를 중단하고 다른 쪽으로 전환할 때, 실패 원인이 장애 자체인지 A/B 설정 차이인지 구분하려고 **설치 파일의 공개 가능 항목**과 **관리 콘솔의 유효 항목**을 각각 비교했다. `proxy-a=node2`, `proxy-b=node3`이다. 암호·서버 주소·원본 TOML 전체의 byte-for-byte 동일성은 공개하거나 비교 결과로 주장하지 않는다.

## 실제 명령은 어디에서 실행됐나

개발자 컴퓨터의 저장소 루트에서 `bash scripts/opensql/capture_live_ha_contract.sh`를 실행했다. 스크립트는 승인된 활성 GCP 계정·프로젝트를 확인한 후, node2/3의 Rocky 컨테이너에서 아래 두 유형을 호출했다.

```bash
# node2/3 컨테이너에서 수집기 실행: 설치 TOML의 허용 키만 JSON화
sudo docker exec --user opensql -i docgrid-nodeN python3 - collect --node nodeN

# node2/3 컨테이너에서 수집기 실행: 관리자 계정은 컨테이너 메모리에서만 사용
sudo docker exec -i docgrid-nodeN python3 - admin --node nodeN
```

| 실행 위치 | 명령 또는 코드 내부 호출 | 목적 | 결과 요약 |
| --- | --- | --- | --- |
| 개발자 컴퓨터의 저장소 루트 | `bash scripts/opensql/capture_live_ha_contract.sh` | A/B 설치·관리 설정을 같은 수집 절차로 확보한다. | node2/3 설치 스냅샷과 proxy-a/b 관리자 스냅샷이 생성되고 `assemble` 검증을 통과했다. |
| node2/3 Rocky 컨테이너 | `python3 - collect --node nodeN` | TOML의 공개 허용 키만 수집한다. | 두 노드에서 `pool_mode=transaction`, parser·읽기/쓰기 분리 활성 등 허용 설치값이 일치했다. |
| node2/3 Rocky 컨테이너 | `python3 - admin --node nodeN` 안의 `SHOW CONFIG` | 런타임 관리 뷰의 유효 설정을 설치값과 별개로 읽는다. | A/B의 허용 관리 설정이 같았다. 풀 캐시 값은 `0`으로, TOML 일반 섹션 `1000`과 달랐다. |
| 같은 관리 세션 | `SHOW STATS` | primary·replica 역할별 통계가 있는지 확인한다. | A/B 모두 primary·replica 누적 카운터가 있었다. 숫자는 서로 달랐으며 이번 시험만의 요청량은 아니다. |
| 같은 관리 세션 | `SHOW SERVERS` | 서버별 상태·캐시 통계의 공개 가능한 결과를 확인한다. | 공개 스냅샷의 `servers=[]`; 이 결과로 물리 DB별 분산을 판정하지 못했다. |
| 개발자 컴퓨터의 저장소 루트 | 수집 스크립트가 호출한 `capture_ha_contract.py assemble` | 두 설치값·두 관리값의 동등성을 검사한다. | 공개 허용 항목 비교를 통과했고, 다섯 스냅샷의 SHA-256은 `0a0b86ef…549794`였다. |

`admin` 모드의 [`admin_rows()`](../../../scripts/opensql/capture_ha_contract.py)는 컨테이너 localhost의 OpenProxy 서비스 포트에 `psql -X -w --csv -P footer=off -h 127.0.0.1 -d openproxy -c 'SHOW CONFIG'` 형태로 접속한다. 같은 방식으로 `SHOW STATS`, `SHOW SERVERS`도 호출했다. 실제 port·관리 사용자·암호는 설치 파일에서 메모리로 읽어 프로세스 환경에만 넣었으며, 공개 명령 예시에는 적지 않는다. `SHOW`는 조회 명령이고 설정 변경은 아니다. 설치 파일의 `admin_port=6433` 경로는 컨테이너 localhost에서 열려 있지 않았고, 서비스 포트 `6432` 경로가 성공했다. 관리 포트의 제품상 의미는 별도 확인 사항이다.

로컬에서 [`assemble`](../../../scripts/opensql/capture_ha_contract.py)을 실행해 다섯 JSON 입력의 허용 필드를 검증·비교했다. 재현 시에는 위 스크립트가 이 단계를 자동으로 실행한다. `assemble`은 `node2.openproxy`와 `node3.openproxy`, `proxy-a.effective_config`와 `proxy-b.effective_config`를 별도 비교하고 다르면 실패한다.

## 비교한 코드·설정과 결과

| 비교 층위 | 실제 확인한 항목 | A/B 결과 | 해석 |
| --- | --- | --- | --- |
| 설치 TOML 일반 섹션 | `connect_timeout=10000`, `prepared_statements_cache_size=1000` | 동일 | 같은 설치 설정의 허용 항목이다. 두 번째 값의 관리자 풀 값과의 우선순위는 별개다. |
| 설치 TOML `pools.docgrid` | `pool_mode=transaction`, `default_role=primary`, `query_parser_enabled=true`, `query_parser_read_write_splitting=true` | 동일 | 라우팅 규칙의 기본 조건이 두 프록시에서 같았다. |
| 설치 TOML shard/user | `use_patroni=true`, `patroni_port=8008`, `pool_size=5` | 동일 | 주소와 인증 정보는 제외한 공통 계약이다. |
| `SHOW CONFIG` 유효 풀 값 | `pool_mode=Transaction`, `default_role=primary`, 읽기/쓰기 분리·parser 활성, `primary_reads_enabled=false`, `load_balancing_mode=Random`, 풀의 `prepared_statements_cache_size=0` | 동일 | 런타임 관리 뷰에서도 허용 항목이 일치했다. 대소문자 차이는 출력 표기다. |
| `SHOW CONFIG` 일반 값 | `connect_timeout=10000`, `shutdown_timeout=60000` | 동일 | 연결·종료 관련 설정을 후속 장애 시험 기준선으로 쓸 수 있다. |
| `SHOW STATS` | 양쪽에 primary·replica 누적 카운터가 존재 | 각각 숫자는 다름 | **설정 비교 대상이 아니다.** 누적 통계가 다르다고 설정 불일치로 판정하면 안 된다. |

증거: [node2 설치 스냅샷](../opensql-contract-evidence/node2.json), [node3 설치 스냅샷](../opensql-contract-evidence/node3.json), [A 관리 스냅샷](../opensql-contract-evidence/proxy-a-admin.json), [B 관리 스냅샷](../opensql-contract-evidence/proxy-b-admin.json), [통합 해시](../opensql-contract-evidence/contract-manifest.json).

## 왜 이것이 필요한가

두 프록시의 공개 가능한 설치·유효 설정이 같아야 A→B 또는 B→A 접속 전환 시험 결과를 해석할 기준선이 생긴다. 예를 들어 한쪽만 read/write splitting이 꺼져 있으면 장애 전후 SQL 역할 차이가 장애의 영향인지 설정의 영향인지 구분되지 않는다. 이번 비교는 그 혼동을 줄인다.

## 결론과 한계

A/B **허용 항목의 설정 일치**는 완료됐다. 원본 TOML 전체·비밀값·서버 주소가 동일하다는 주장은 하지 않는다. `SHOW SERVERS`의 공개 스냅샷은 `servers=[]`여서 물리 DB별 라우팅 증거가 아니다. 풀 캐시 값 `0`과 일반 TOML `1000`의 실제 우선순위도 확정하지 못했다. 그리고 양쪽의 설정이 같아도 한쪽 장애 뒤 실제 새 연결이 다른 쪽으로 넘어가는지는 아직 시험하지 않았다.
