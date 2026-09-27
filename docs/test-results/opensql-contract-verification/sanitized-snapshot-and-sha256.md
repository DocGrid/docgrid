# 비식별 수집 JSON과 SHA-256 재계산

## 검증 대상

노드 3개와 OpenProxy 관리자 스냅샷 2개를 **사람이 수작업으로 옮겨 적지 않고**, 공개 가능한 항목만 모아 하나의 canonical JSON 스냅샷으로 만들었는지 확인했다. SHA-256은 그 공개 스냅샷의 무결성 확인값이다. GCP·티맥스티베로의 서명이나 원본 서버 전체 상태에 대한 증명서는 아니다.

## 실제 실행 명령과 장소

개발자 컴퓨터의 저장소 루트에서 `bash scripts/opensql/capture_live_ha_contract.sh`를 실행했다. 승인 계정/프로젝트를 확인한 뒤, 스크립트가 각 GCP VM에 SSH로 들어가 Rocky 컨테이너의 `collect`, VM 호스트의 `runtime`, node2/3 컨테이너의 `admin`을 호출했다. 로컬의 `merge`가 같은 노드 별칭인 두 결과만 합치고, `assemble`이 최종 5개 입력을 검증·해시했다. 명령 구조는 다음과 같다.

```bash
python3 scripts/opensql/capture_ha_contract.py merge \
  <nodeN.collect.json> <nodeN.runtime.json>
python3 scripts/opensql/capture_ha_contract.py assemble \
  <node1.json> <node2.json> <node3.json> \
  --admin <proxy-a-admin.json> <proxy-b-admin.json>
```

꺾쇠 안은 스크립트가 만든 **임시 파일 경로의 설명용 자리표시자**이며, 실제 스크립트는 `mktemp -d`로 임시 디렉터리를 만들고 종료 시 삭제한다. 공개 증거는 [`opensql-contract-evidence`](../opensql-contract-evidence/contract-manifest.json)에 복사한다. 원격 관리 암호는 컨테이너 메모리에서만 읽고, 공개 JSON에는 설정 허용 키·역할별 누적 숫자만 들어간다.

이번 문서를 작성하면서 개발자 컴퓨터의 같은 worktree에서 실제로 아래 독립 재계산 명령도 실행했다. 이는 **수집 당시 명령이 아니라 공개 결과를 나중에 검산한 명령**이다.

```bash
python3 -c 'import json,hashlib,pathlib; p=pathlib.Path("docs/test-results/opensql-contract-evidence/contract-manifest.json"); d=json.loads(p.read_text()); b=json.dumps(d["snapshot"],ensure_ascii=False,sort_keys=True,separators=(",",":")).encode(); print(hashlib.sha256(b).hexdigest()); print(d["evidence_sha256"]); print(hashlib.sha256(b).hexdigest()==d["evidence_sha256"])'
```

| 실행 위치 | 명령·수집기 모드 | 목적 | 결과 요약 |
| --- | --- | --- | --- |
| 개발자 컴퓨터의 저장소 루트 | `bash scripts/opensql/capture_live_ha_contract.sh` | 승인 GCP 환경에서 다섯 스냅샷을 자동 수집한다. | 2026-09-26 12:57:23~12:58:14 UTC에 노드 3개·관리자 2개를 수집했다. |
| 각 GCP VM/컨테이너 | 수집기의 `collect`, `runtime`, `admin` | 설치값·호스트 실행 상태·프록시 관리값을 분리해서 읽는다. | 노드 3개, proxy-a/b 관리자 2개의 비식별 JSON이 생성됐다. |
| 개발자 컴퓨터 | `capture_ha_contract.py merge` | 같은 별칭의 컨테이너/호스트 결과만 결합한다. | node1/2/3 각각의 병합이 성공했고 중복·별칭 불일치가 관측되지 않았다. |
| 개발자 컴퓨터 | `capture_ha_contract.py assemble` | 다섯 결과의 허용 항목·A/B 일치 여부를 검사하고 canonical JSON을 해시한다. | 검증을 통과했고 SHA-256 `0a0b86ef…549794`를 manifest에 기록했다. |
| 개발자 컴퓨터, 문서 작성 시 재검산 | 위의 `python3 -c 'import json,hashlib,pathlib; ...'` | 공개 manifest의 `snapshot`에서 해시를 독립 재계산한다. | 재계산값과 기록값이 같았고 비교 결과가 `True`였다. 원격 상태를 새로 수집한 것은 아니다. |
| 개발자 컴퓨터 | `python3 -m unittest scripts.opensql.test_capture_ha_contract -v` | 비식별화·병합·해시의 코드 방어 동작을 확인한다. | 단위시험 `11개`, 실패 `0건`. 원격 서버 상태의 진실성 증명은 아니다. |

## 코드가 하는 검증

[`merge_node()`](../../../scripts/opensql/capture_ha_contract.py)은 컨테이너 결과와 호스트 결과의 노드 별칭이 다르면 실패한다. [`assemble()`](../../../scripts/opensql/capture_ha_contract.py)은 node1/2/3이 정확히 하나씩 있는지, OS가 모두 Rocky 9.7 `x86_64`인지, A/B 설치·관리 계약 값이 같은지, OpenSQL 버전과 Patroni 동적 설정이 같은지 검사한다. 민감 문자열 탐지도 통과해야 한다. 그 뒤 `snapshot`을 `ensure_ascii=False`, `sort_keys=True`, `separators=(",",":")`로 직렬화한 **UTF-8 바이트**에 SHA-256을 적용한다. 수집 시작/종료 시각은 해시 대상 `snapshot`의 바깥 필드다.

`python3 -m unittest scripts.opensql.test_capture_ha_contract -v`로 수집기 단위시험 11개가 실패 없이 통과했다. 여기에는 비밀값 차단, 중복 감지, 버전 추출, etcd 시간 입력 우선순위, 자동 병합, 관리 설정 포함 해시, JUnit 비식별화 검증이 있다. 이 단위시험은 원격 상태의 진실성을 보장하는 시험이 아니라 **수집 코드의 방어 동작**을 확인한 것이다.

## 관측 결과와 해석

| 항목 | 결과 | 해석 |
| --- | --- | --- |
| 순차 수집 구간 | 2026-09-26 `12:57:23Z`~`12:58:14Z` | 원자적 단일 시점 스냅샷이 아니다. |
| 입력 | 노드 3개, 관리자 결과 2개 | 모두 [manifest](../opensql-contract-evidence/contract-manifest.json)의 `snapshot`에 포함됐다. |
| 공개 SHA-256 | `0a0b86eff71c4c24740a99ea0f334fca70e5c11e1a813c3a3bc646a825549794` | 공개 snapshot 바이트의 동일성 검사용이다. |
| 독립 재계산 | 출력 해시 두 줄 동일, 비교 `True` | 게시된 manifest의 `evidence_sha256`이 현재 `snapshot`에서 재현됐다. |
| 민감 값 | 계정·프로젝트 ID·내부 IP·암호·라이선스 파일 미포함 | 공개 문서로 올릴 수 있는 범위를 유지했다. 자동 필터를 통과해도 게시 전 리뷰가 필요하다. |

## 한계

원본 설정 전체가 아닌 **허용 목록**만 해시한다. 다른 시점에 다시 수집하면 누적 `SHOW STATS` 숫자가 변해 해시도 달라질 수 있다. 해시가 같아도 당시 서버가 정직했음을 증명하는 것은 아니며, 해시가 달라도 곧바로 설정 오류라는 뜻은 아니다. JUnit 결과와 이전 prepared-cache delta는 이 해시 입력에 포함되지 않고 실행 시점도 다르다. 따라서 세 증거를 한 번의 원자적 실험으로 묶지 않는다.
