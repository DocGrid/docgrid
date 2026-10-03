# #401 PID 1·Patroni 강등 대기 원인 대조

- 실행 ID: `issue-401-causal-review-01`
- 목적: 수정 전 `stopping`·postgres 좀비와 수정 후 자동 재합류를 설치된 Patroni 코드의 종료 대기 동작에 연결하되 관측 이상의 단정은 하지 않는다.
- 실행 위치: GCP DB 컨테이너의 읽기 전용 프로세스·설치 소스 검사, 양방향 시험 로그 대조.
- 절차: 세 현재 컨테이너의 PID 1·좀비 개수 확인 → 설치된 Patroni 버전·postmaster 종료 대기 코드의 존재 확인 → 수정 전후 역할 이전 결과 비교.
- 성공 기준: 현재 세 노드의 PID 1 reaping init, PostgreSQL 좀비 0; 코드 수준에서 종료 대기·init 회수 계약 확인. 스택 추적이나 단일 원인 증명이 없으면 그 한계를 명시한다.
- 관측: 현재 세 컨테이너 모두 PID 1 `docker-init`, 좀비 프로세스 **0/0/0개**, PostgreSQL 계열 좀비 **0/0/0개**. 수정 전 재현에서는 old node2가 `stopping`일 때 컨테이너 내부 좀비 **5개** 중 PostgreSQL 계열 **1개**였고, 최근 Patroni 로그 120줄에 `demote in progress` **12줄**이었다.
- 소스 확인: 설치 버전은 **Patroni 4.0.5**. 처음에는 영속 볼륨에서 `postmaster.py`를 찾지 못해 소스 경로 조회가 종료 코드 **1**이었다. 이어 [동일 버전 공식 소스의 postmaster 시작 코드](https://github.com/patroni/patroni/blob/v4.0.5/patroni/postgresql/postmaster.py#L214-L225)와 [stop 대기 코드](https://github.com/patroni/patroni/blob/v4.0.5/patroni/postgresql/__init__.py#L835-L923)를 GitHub API로 직접 조회했다. `PostmasterProcess`는 `psutil.Process`를 확장하고, postmaster는 분리 실행 후 init의 프로세스 회수에 의존한다. stop 경로는 `postmaster.wait(timeout=stop_timeout)`으로 종료를 기다린다.
- 비교: 예전 PID 1 `sleep` 구성에서 switchover 후 `stopping` 재현; 세 노드에 reaping init을 적용한 뒤 같은 양방향 계획 switchover에서 이전 leader가 **19초/18초** 내 streaming으로 복귀하고 Docker 컨테이너 재시작 **0회**.
- 해석: 고아 postmaster의 좀비 상태가 PID 1에서 회수되지 않아 Patroni의 종료 감지가 진행되지 않았다는 메커니즘을 **강하게 지지**한다. 다만 멈춘 당시 Patroni 스레드 스택을 채집하지 않았고 동시 변경(컨테이너 재생성)도 있으므로 유일한 원인을 수학적으로 증명했다고 표현하지 않는다. Docker의 [`--init` 문서](https://docs.docker.com/engine/containers/multi-service_container/) 역시 이 reaping 역할을 설명한다.
