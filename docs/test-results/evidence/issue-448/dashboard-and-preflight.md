# 관측 화면·사전/사후 안전 점검

- 실행 ID: `ha448pre01`; 관측일: 2026-10-09 KST; 위치: GCP API, 관측 VM, DB 3대, 부하 VM.
- 목적: 실제 장애 전후 접속·복제·백업 관문을 확인하고 Grafana가 실제 시계열을 읽는지 검증한다.
- 성공 기준: VM 8/8, LB 2/2, primary 1·replica 2, etcd 3/3, OpenProxy 2/2, 관측 대상 5/5, Dashboard 조회 가능. 값이 불명확하면 장애 주입 중단.

| 위치·명령/방법 | 결과 요약 | 해석 |
| --- | --- | --- |
| 로컬 GCP API: `gcloud compute instances list --format=json(name,zone,status)` | 시험에 필요한 VM **8/8 RUNNING**; 별도 과거 VM 1대는 TERMINATED | 장애 전 구성요소가 실제 기동 중이다. VM 수와 DB/프록시 논리 수는 다르다. |
| GCP API: `backend-services get-health` | LB 앱 A/B **HEALTHY 2/2** | 내부 HTTP 경로의 사전 관문 통과. |
| DB 노드: Patroni `/cluster`, `permission-node-probe health` | node3 leader 1, node1/2 streaming replica 2, lag **0/0**, primary 복제 연결 **2** | 당일 실제 역할 확인. 이전 문서 값을 재사용하지 않았다. |
| DB 노드: etcd endpoint health/member 조회 | 건강한 멤버 **3/3** | 정족수 관문 통과. 이번에는 etcd를 중단하지 않았다. |
| DB 노드: OpenProxy process/port 상태 | A/B **2/2 ready** | 장애 전에 프록시 중복 경로가 살아 있다. |
| GCP API: DB 디스크 snapshot 상태 | 관련 snapshot **8개 READY** | 복구 수단 확인. 이번 프로세스 장애에서 snapshot restore는 필요하지 않았다. |
| 관측 VM: Grafana Dashboard API 가져오기·읽기 | UID `docgrid-opensql-ha-live`, Row 포함 패널 **8개**, Prometheus UID `ha-prometheus`; 최종 재가져오기 `description_match=true` | JSON을 파일로만 작성한 게 아니라 실제 관측 VM에 배포했다. |
| 관측 VM: Prometheus `/api/v1/query` | 앱·Patroni 대상 **5/5**, primary=1 값 1개, replica WAL 패널 **2개·0 Byte** | 화면 패널이 실제 숫자를 반환한다. WAL receive−replay만으로 전체 복제 지연은 알 수 없다. |
| 관측 VM: `count_over_time`으로 실행별 k6 시계열 조회 | 기준선 201 표본 **13개**, 프록시 500 표본 **1개**, primary VU 표본 **25개** | 세 실행의 지표가 Remote Write를 거쳐 조회됨. 표본 수는 요청 건수가 아니다. |
| 로컬: `python3 -m unittest monitoring.grafana.tests.test_ha_dashboard -v` | **5/5 통과** | 데이터소스·패널 겹침·실패 버킷·run_id 범위·WAL replica 한정 정적 검증. |
| 로컬: 기존 `validate_dashboard.py`를 HA JSON에 직접 적용 | 전용 기존 Dashboard UID를 요구해 **적용 불가** | HA 전용 5건 테스트를 쓴다. 이 진단 실패를 HA JSON 통과로 바꾸어 말하지 않는다. |
| 장애 종료 후 GCP API/DB/관측 VM 재조회 | LB **2/2 HEALTHY**, leader 1·replica 2 streaming lag0, OpenProxy A/B ready, Prometheus/Grafana running | 시험 후 서비스 복구 확인. |

관측 VM의 Prometheus는 내부 주소에만 바인딩돼 있어 `127.0.0.1:9090` 첫 조회는 **연결 실패**였다. 실제 바인딩된 내부 주소로 수정해 위 시계열을 조회했다. 내부 주소값은 명령 출력·문서에 기록하지 않았다. Prometheus의 `up=1`과 DB 요청 ID 정합성은 서로 다른 증거다.
최종 Grafana 재가져오기 뒤 임시 파일 제거의 첫 IAP 시도는 실패했고, 재시도에서 두 파일 모두 없는 것을 확인했다. 관리자 비밀번호는 관측 VM의 보호된 파일에서만 읽었으며 값은 출력하지 않았다.
