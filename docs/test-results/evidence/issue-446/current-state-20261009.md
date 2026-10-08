# OpenSQL HA 관측 도입 전 현재 상태 — 2026-10-09

- 실행 ID: `ha446inventory20261009a`
- 수집 종료: 2026-10-09 04:38 KST (2026-10-08 19:38 UTC)
- 목적: 이전 시험 문서의 VM·배포·클러스터 상태를 현재 상태로 가정하지 않는다.
- 실행 위치: 로컬 `gcloud` 읽기 명령과 GCP VM 게스트의 읽기 전용 명령. VM 명칭·내부 주소·프로젝트 ID·계정·비밀은 기록 전에 제외했다.
- 성공 기준: 앱 2대·DB 3대·부하 VM·캐시 VM 실행, LB 2/2, 동일 배포 JAR, Patroni 리더 1·replica 2, etcd 멤버 3/3 건강, 프록시 A/B 실제 프로세스·6432 리스너 확인.

| 확인 위치·목적 | 명령 또는 읽기 방법 | 결과 요약 | 해석 |
| --- | --- | --- | --- |
| 로컬 GCP API · VM 목록 | `gcloud compute instances list --format=json` 후 역할·상태만 출력 | 앱 **2/2**, DB **3/3**, 부하 **1/1**, 공용 캐시 **1/1 RUNNING**. 기존 비관측 VM **1대 TERMINATED** | 게스트 내부 서비스 건강을 뜻하지 않는다. |
| 로컬 GCP API · LB | `gcloud compute backend-services get-health` 결과의 상태만 집계 | 내부 앱 LB 백엔드 **2/2 HEALTHY** | HA 쓰기 성공·DB 복제 상태를 뜻하지 않는다. |
| 앱 A/B · 배포 일치 | IAP SSH에서 `systemctl is-active docgrid`, 실행 JAR의 `sha256sum`, `curl localhost:8081/actuator/prometheus` | 서비스 **2/2 active**, JAR SHA-256 **두 VM 동일** (`8510908ce6f5454d0e6cd64fa5c4de3b2b0ddf5ee63602eb8fab4c0fc46297fb`), metrics HTTP **200/200** | JAR 비트 동일성은 확인. 현재 소스 커밋은 JAR만으로 역추정하지 않았다. |
| DB 3대 · Patroni | 각 노드 `GET /patroni`; node1 `GET /cluster` | node3 **primary**, node1·node2 **replica**, 모두 `running`, timeline **14**, 클러스터 역할 **leader 1·replica 2** | 역할과 REST 응답은 수집 시점 관측. 실제 장애 결과가 아니다. |
| DB 3대 · etcd | 각 노드 로컬 `GET /health`; 한 노드의 읽기 전용 `POST /v3/cluster/member/list` | 로컬 health **3/3 true**, 실행 멤버 **3개** | 정족수 정상 상태만 확인. etcd 장애를 주입하지 않았다. |
| DB node2·node3 · OpenProxy | 프로세스 목록과 TCP 6432 `LISTEN` 확인 | 각 노드 프로세스 **1개**, 6432 리스너 **1개**. node1 프록시 **0개** | 이름을 모르는 systemd 유닛의 `inactive`만으로 프록시 중단이라 판정하지 않았다. |
| 앱·DB · metrics endpoint | 앱 A/B loopback 8081, DB 3대 loopback 8008 | 앱 **2/2 HTTP 200**, Patroni **3/3 HTTP 200** | 관측 VM에서의 네트워크 접근은 별도 시험으로 판정한다. |

게스트 접근에는 **30분 자동 만료 OS Login 키**를 사용했다. 로컬 개인키는 명령 종료 시 삭제했다. 실행 중 상태가 바뀔 수 있으므로 이 표를 이후 장애 시험의 사전 점검으로 재사용하지 않는다.
