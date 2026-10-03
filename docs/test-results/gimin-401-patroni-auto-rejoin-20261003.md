# OpenSQL 계획 switchover 이전 리더 자동 재합류 수정·검증

- 관련 이슈: [#401](https://github.com/DocGrid/docgrid/issues/401)
- 실행일: 2026-10-03 (KST). 개별 명령의 절대 시각은 일관되게 수집하지 못했으므로 아래 초 단위 값은 **명령 내부 경과 시간/5초 간격 관측값**으로만 해석한다.
- 범위: 시험 전용 Rocky Linux 9.7 x86_64 DB VM 3대의 Docker PID 1/Patroni 계획 switchover. 앱 HTTP 부하, 비계획 리더 상실, RTO/RPO는 별도 시험이다.
- 최종 상태: PID 1 reaping init **3/3**, Patroni leader **1**·streaming replica **2**, etcd healthy **3/3**, 양방향 자동 재합류 **2/2**, 수동 컨테이너 재시작 **0회**, 합성 계정 **0명**·잔여 타이머 **0/3**.

## 문제와 원인 가설

이전 계획 switchover에서는 새 primary가 선출돼도 old leader가 `Replica:stopping`에 머물렀다. 이번 수정 전 반복에서는 PostgreSQL 로컬 준비 실패, 좀비 프로세스 **5개 중 PostgreSQL 계열 1개**, 최근 Patroni 로그 120줄 중 `demote in progress` **12줄**을 다시 관찰했다. 당시 PID 1은 `sleep`, Docker init은 꺼져 있었다.

설치 버전 **Patroni 4.0.5**의 [postmaster 시작 코드](https://github.com/patroni/patroni/blob/v4.0.5/patroni/postgresql/postmaster.py#L214-L225)는 분리 실행된 프로세스의 회수를 init에 맡긴다고 설명한다. [stop 경로](https://github.com/patroni/patroni/blob/v4.0.5/patroni/postgresql/__init__.py#L835-L923)는 `postmaster.wait(timeout=stop_timeout)`으로 종료를 기다린다. PID 1이 고아 자식을 회수하지 않으면 종료된 postmaster가 좀비로 남아 대기 완료가 지연될 수 있다는 것이 이 수정의 기전이다. Docker도 [`--init`의 자식 회수 역할](https://docs.docker.com/engine/containers/multi-service_container/)을 문서화한다. 멈춤 당시 Patroni 스레드 스택은 수집하지 않았으므로 **유일한 원인의 확정**이 아니라 로그·프로세스·수정 전후 재현이 일치하는 강한 근거로 표현한다.

```text
수정 전: Patroni → 분리된 postmaster → 종료 → PID 1(sleep)이 회수하지 않음
                                 └─ 좀비 유지 → Patroni stopping 반복

수정 후: Patroni → 분리된 postmaster → 종료 → PID 1(docker-init)이 회수
                                 └─ 종료 감지 → 이전 leader가 replica로 재합류
```

## 변경 내용과 보존 원칙

| 구성 | 작업 | 이유 |
| --- | --- | --- |
| `scripts/opensql/ha_node_container.py` | 노드별 기존 writable layer를 로컬 이미지로 `capture`; 이미지·CPU·메모리·mount·네트워크·환경 계약을 검사한 뒤 `--init`으로 `create`/`verify` | 기본 Rocky 이미지에는 기존 컨테이너 대비 RPM **21개**가 부족하고 overlay 변경이 **2,648개**여서 기본 이미지로 재생성하면 실행 환경을 잃는다. 기존 데이터 bind mount는 이미지 캡처에 포함하지 않는다. |
| `scripts/opensql/test_ha_node_container.py` | 이미지 캡처·runtime 계약·기존 컨테이너/활성 데이터 mount 거부 단위 시험 **6건** | 같은 data directory에 두 컨테이너가 동시에 붙거나 원래 설치를 잃는 실수를 차단한다. |
| 실제 GCP DB VM 3대 | node1·node3 replica부터 한 대씩 교체, primary를 node1로 옮긴 후 node2 교체 | 한 번에 DB와 etcd 멤버 하나만 줄이고 매 단계 leader 1·streaming replica 2·etcd 3/3을 확인한다. |
| 보존 자산 | 기존 컨테이너는 node별 stopped 상태, 캡처 이미지는 local-only, 신규 디스크 snapshot 3개는 READY | 교체 실패 시 복구 지점을 유지한다. 이 자산들은 저장 공간·snapshot 비용을 계속 사용하며 자동 삭제하지 않았다. |

공개 코드에는 실제 GCP 프로젝트 ID, IP, VM hostname을 하드코딩하지 않았다. 노드 식별자는 실행 시 받으며, 공개 태그는 `node1/2/3`이라는 논리 라벨만 사용한다. 실제 VM의 컨테이너 이미지 ID와 공개용 태그의 ID 일치 및 runtime 계약을 최종 **3/3** 재검증했다.

## 실행 관문과 결과

| 단계·실행 위치 | 명령/방법(비식별) | 주요 결과 | 해석 |
| --- | --- | --- | --- |
| 로컬 | `python3 -m unittest discover ... test_ha_node_container.py` | **6/6 PASS**, 문법·diff 공백 오류 0 | 생성/거부 조건은 로컬에서 통과. 실환경 재합류의 증거는 아님. |
| GCP 후보 컨테이너 | 기본 Rocky와 기존 이미지의 RPM 비교, `docker run --init`/PID 1/좀비 확인 | 기존 **168개**, 기본 **147개**, 누락 **21개**. 캡처 이미지 후보 RPM **168/168**, PID 1 `docker-init`, 좀비 **0개** | 기본 이미지로 직접 재생성하지 않고 writable layer를 보존했다. |
| GCP 백업 | Patroni/etcd 확인, `etcdctl snapshot save`, 부착 디스크 snapshot | leader 1·streaming 2·etcd **3/3**. etcd snapshot **1,536,032 B/mode 0600**, 디스크 snapshot READY **3/3** | 교체 전 복구 지점 확보. 전체 클러스터 복원 시험은 아님. |
| GCP 순차 교체 | old container stopped 보존 → `create` → 기존 bootstrap → `verify` | 세 새 컨테이너 PID 1 reaping init **3/3**, 각 단계 leader 1·streaming 2·etcd **3/3** | DB 데이터와 기존 자원을 유지해 재합류. |
| 수정 전 역할 이전 | `patronictl switchover`, 내부 `ps`, 로그 비식별 집계 | 새 leader 선출, old leader `stopping`, PostgreSQL 좀비 **1개**, 강등 반복 **12줄** | 원래 결함 재현. 이 시점의 old 컨테이너는 이후 stopped로 보존. |
| 수정 후 A: node1→node2 | 계정 4명·독립 타이머 3개, switchover, new primary에서 삭제 | 명령 **4초**, 전체 streaming 복귀 관측 **19초**, old 컨테이너 재시작 **0**, 계정 **0**, 타이머 **0/3** | 자동 재합류 통과. |
| 수정 후 B: node2→node1 | 별도 계정 4명·타이머 3개로 반대 방향 반복 | 명령 **4초**, 전체 streaming 복귀 관측 **18초**, 재시작 **0**, 계정 **0**, 타이머 **0/3** | 반대 방향도 통과. |
| GCP 최종 정리 | OpenProxy 프로세스 확인, 인스턴스 전용 임시 SSH 키 제거 | OpenProxy **1/1**, temporary key 항목 **0/3**, 로컬 임시 키 **0개** | DB/etcd 외 프록시 기동과 임시 접근 정리 확인. 프록시 요청 성공률은 별도 시험이다. |

## 실패·재시도도 보존

초기 격리 시험 두 번은 기본 이미지에 `ps`가 없는 점과 `docker top`의 PID 열 요구로 종료 코드 127/1이 났다. 데이터를 건드리지 않는 후보 컨테이너였고 모두 삭제했다. 이를 수정한 `/proc`·PID 포함 `docker top` 시험은 통과했다. 수정 전 역할 이전의 첫 사전 검사는 Docker hostname과 Patroni 멤버 이름을 같다고 가정해 안전하게 중단됐고, 실제 멤버 이름을 동적으로 읽도록 보정했다. 마지막 공개용 도우미 재배포의 첫 명령은 원격 셸 변수가 초기화되지 않아 태그·verify가 **3/3 실패**했지만 DB 재시작은 없었다. 별도 재실행에서 태그 ID·runtime 계약 **3/3** 통과했다. `py_compile`의 첫 로컬 실행은 작업 트리 캐시 쓰기 권한 때문에 실패했고, 임시 캐시 경로를 지정한 동일 문법 검사는 통과했다. 이 실패를 PASS에 섞지 않았다.

## 증거와 남은 범위

상세 실행은 [`evidence/issue-401/`](evidence/issue-401/)에서 목적·반복별 파일로 분리했다. 특히 [수정 전 재현과 node2 교체](evidence/issue-401/20-primary-handoff-node2-migration-retry.md), [A 방향](evidence/issue-401/21-fixed-switchover-node1-to-node2.md), [B 방향](evidence/issue-401/22-fixed-switchover-node2-to-node1.md), [원인 기전 대조](evidence/issue-401/23-causal-mechanism-review.md), [임시 접근 정리](evidence/issue-401/27-final-services-and-key-cleanup.md)를 함께 봐야 한다.

**아직 증명하지 않은 것:** HTTP 쓰기 중 primary 강제 종료의 RTO/RPO, 성공 응답 ID의 누락·중복, VM 상실, etcd 정족수 상실, 장시간 soak, 실제 앱·OpenProxy 요청의 오류율. **19초/18초를 서비스 RTO나 무손실 보장으로 사용하면 안 된다.** 기존 컨테이너·로컬 이미지·신규 디스크 snapshot은 복구용으로 보존했다. 안정 기간 후 삭제 여부를 별도 결정해야 하며, 디스크 snapshot의 비용은 계속 발생한다.
