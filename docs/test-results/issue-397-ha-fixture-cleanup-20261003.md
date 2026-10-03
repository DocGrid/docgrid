# HA 시험 계정 정리 가드 변경과 검증 — 이슈 #397

기록 시각: 2026-10-03 13:20 KST. 각 명령의 정확한 시작 시각은 별도 수집하지 못했다. 아래 로컬 결과는 **GCP 리더 이동 실측 결과가 아니다**. 프로젝트·내부 주소·계정·비밀값은 기록하지 않았다.

## 문제와 변경

기존 `permission_fixture.sh`는 node1의 로컬 PostgreSQL이 primary일 때만 모든 동작을 허용했다. node1이 replica가 되거나 VM이 중단되면 기존 단일 VM 타이머만으로 합성 ADMIN 계정 삭제를 보장할 수 없다. 이번 변경은 각 노드에 기존 정리 도우미를 설치하고, 세 개의 독립 타이머를 확인한 뒤에만 네 개의 실행별 합성 계정을 생성하도록 오퍼레이터 도우미를 추가한다. 삭제는 관측된 현재 primary에서만 한다.

```text
시험 전: node1 timer ──▶ node1이 primary일 때만 삭제
                           └─ node1 상실/역할 이동 → 정리 실패 가능

변경 설계: node1 timer ─┐
          node2 timer ─┼─▶ 각자 로컬 역할 확인 ─▶ 현재 primary만 삭제
          node3 timer ─┘           └─ replica/DB 불가 → 타이머 유지·재시도

prepare: 역할 1/2 확인 → 3/3 timer 무장 확인 → primary에서 4계정 생성
cleanup: 현재 primary 삭제 → 각 생존 replica에 삭제 복제 확인 → timer 해제
```

| 파일 | 변경 목적 |
| --- | --- |
| `scripts/opensql/permission_fixture.sh` | 역할 조회를 분리하고 replica의 타이머 무장·상태 확인을 허용. 생성·삭제는 현재 primary에서만 수행 |
| `scripts/opensql/ha_fixture_cleanup.py` | 세 VM의 역할·타이머를 함께 확인하고 생성·정리를 순서대로 실행. 한 VM이 불가하면 정리 완료를 선언하지 않음 |
| `scripts/opensql/test_ha_fixture_cleanup.py` | 세 타이머 미무장, node1 상실·node2 승격, 삭제 실패, 이중 primary 관측을 결정적으로 검사 |

## 실행별 검증

| 실행 ID·목적 | 위치·명령 | 관측 수치·결과 요약 | 판정·기록 |
| --- | --- | --- | --- |
| `fixture-397-import-01` 테스트 호출 방식 확인 | 로컬, `python3 -m unittest scripts/opensql/test_ha_fixture_cleanup.py -v` | **1건 로드 오류**. `permission_replica_lag` 모듈을 찾지 못함. 제품 코드 실패가 아니라 이 호출 방식의 검색 경로 문제 | 실패 기록: [호출 방식](evidence/issue-397/01-local-import.md) |
| `fixture-397-unit-02` 새 정리 로직 | 로컬, `python3 -m unittest discover -s scripts/opensql -p 'test_ha_fixture_cleanup.py' -v` | **6/6 통과**, 실패 0. node1 상실 때 node2 삭제·접근 불가 타이머 미해제 검증 | 로컬 모형 통과: [정리 로직](evidence/issue-397/02-local-unit.md) |
| `fixture-397-unit-06` primary 미확인 차단 추가 | 로컬, 같은 `unittest discover` 명령 재실행 | **7/7 통과**, 실패 0. primary가 하나도 보이지 않으면 삭제·타이머 취소 0건 | 로컬 모형 통과: [추가 회귀](evidence/issue-397/06-local-no-primary.md) |
| `fixture-397-regression-03` 기존 권한 시험 | 로컬, `python3 -m unittest discover -s scripts/opensql -p 'test_permission_replica_lag.py' -v` | **6/6 통과**, 실패 0 | 로컬 회귀 통과: [기존 시험](evidence/issue-397/03-local-regression.md) |
| `fixture-397-syntax-04` 쉘·패치 검사 | 로컬, `bash -n scripts/opensql/permission_fixture.sh`, `git diff --check` | 두 명령 모두 종료 코드 **0** | 정적 검사 통과: [구문 검사](evidence/issue-397/04-local-syntax.md) |
| `fixture-397-gcp-05` 실제 게스트 접근 준비 | GCP IAP 경유 기존 키·저장된 호스트 키로 node1 읽기 전용 접속 | 대상 프로젝트·VM·등록 키·호스트 키 매칭 확인, SSH 공개키 인증 거부. VM 파일 변경 **0건**, 계정 생성 **0건** | 실환경 시험 미실행: [접속 관문](evidence/issue-397/05-gcp-readiness.md) |

## 제한과 후속 관문

- 새 코드와 타이머는 **아직 GCP 세 게스트에 배포하지 않았다**. replica에서의 `systemd-run` 반복 실행과 실제 leader switchover 후 삭제는 실측되지 않았다. 로컬 테스트는 원격 명령·타이머를 모의한 결과다.
- GCP 접속이 복구되면 기존 파일의 해시와 백업을 확인하고 **같은 버전의 `permission_fixture.sh`를 세 노드 모두에 설치**한다. `ha_fixture_cleanup.py`는 배포를 대신하지 않는다. 설치 해시 3/3 일치와 호스트별 Docker 컨테이너 이름을 확인한 뒤에만 실행한다.
- 오퍼레이터는 승인된 GCP 계정·프로젝트·zone·기존 SSH 키를 `OPENSQL_EXPECTED_ACCOUNT`, `OPENSQL_EXPECTED_PROJECT`, `OPENSQL_GCP_ZONE`, `OPENSQL_SSH_KEY`로 제공한다. `prepare <12자리 실행 ID>`가 세 타이머를 무장하고 네 계정을 만든다. 시험 종료 후 `cleanup <같은 실행 ID>`가 현재 primary에서 삭제하며 각 노드의 0건·타이머 해제를 확인한다. 이 명령은 이번에 GCP에서 실행하지 않았다.
- 먼저 장애 없이 3/3 타이머 무장 → 네 계정 생성 → 현재 primary 삭제 → 각 노드 잔여 0·타이머 해제를 연습한다. 이후 실제 primary 장애 시험을 별도 실행한다.
- node1 상실처럼 한 VM이 돌아오지 않은 경우 계정은 생존 primary에서 삭제될 수 있으나, 사라진 VM의 타이머는 확인할 수 없다. 도우미는 이를 성공으로 숨기지 않고 재감사 필요 오류로 남긴다.
- 이 변경은 etcd·PostgreSQL 백업 복구나 HA의 RTO·RPO를 검증하지 않는다. [사전 게이트](https://github.com/DocGrid/docgrid/pull/396)의 나머지 조건도 별도로 확인해야 한다.
