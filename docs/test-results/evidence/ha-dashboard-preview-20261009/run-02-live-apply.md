# HA 대시보드 실제 적용 확인 — ha-dashboard-live-20261009-01

| 항목 | 기록 |
| --- | --- |
| 실행·확인 시각 | 2026-10-09 14:32:45 KST |
| 목적 | 변경한 Grafana 대시보드를 관측 VM에 적용하고 기존 수집 경로·새 PromQL 응답을 확인 |
| 실행 위치 | GCP 내부 관측 VM의 Prometheus·Grafana; 로컬 IAP SSH 터널은 화면 확인용 |
| 코드 기준 | `test/448`의 `c794914` 기반 미커밋 대시보드 변경 |
| 사전 조건 | 기존 대시보드 8패널의 ID·제목·쿼리 해시가 저장소 기준과 일치; 앱 2대·Patroni 3대 active target **5/5 up** |
| 방법 | Prometheus API에서 필요한 지표명·새 패널 PromQL 조회 → Grafana 기존 JSON을 0600 백업 → Dashboard API로 JSON 가져오기 → API 재조회 → 임시 OS Login 키 제거 |
| 통과 기준 | DB 상태 결과 3개(Primary 1·Replica 2), 현재 primary·timeline 각 1개, 앱 지표 각 2개, Grafana 12패널·상태 타임라인 2개·숫자 카드 2개, 임시 키 잔존 0개 |
| 실제 결과 | DB 역할 **3개(Primary 1·Replica 2)**, primary 카드 **1개**, 해당 timeline **14**, 앱 5xx·Hikari timeout 시계열 **각 2개·현재 값 모두 0**, Grafana 가져오기 `success`, UID 유지·버전 **2→3**·패널 **8→12**, 임시 OS Login 키 잔존 **0개**, 로컬 개인키·공개키 삭제 |
| 화면 접근 | 기존 로컬 포트는 다른 SSH 프로세스가 점유해 새 로컬 포트로 터널을 열었고 Grafana 로그인 HTTP **200** 확인. 사용자 로그인 후 실제 화면에서 primary 카드 **1개**, timeline 카드 **14**, 앱 상태 **2행**, DB 역할 **3행(Primary 1·Replica 2)**의 색·문구를 확인 |
| 미실행 | 장애 주입, OpenProxy TCP probe, 앱→프록시 연결 지표, 장애 annotation 이벤트 기록, HTTP 쓰기 부하·DB 요청 ID 대조 |

처음에는 관측 VM 호스트의 loopback Prometheus 주소로 요청해 연결에 실패했다. Prometheus가 컨테이너 내부 네트워크에서만 응답하는 실제 구성을 확인해, 컨테이너 내부 주소를 쉘 변수로만 사용하여 재조회했다. 내부 주소 자체는 이 기록에 남기지 않았다.

Grafana 대시보드 원본 백업은 관측 VM의 소유자 전용 임시 파일에 남겼다. 관리자 암호는 VM 내부의 기존 보호 파일에서 API 인증에만 사용했고 명령 결과·시험 기록에 출력하지 않았다. 이 실행은 **정상 상태의 지표·API·화면 렌더링 확인**이며, 실제 장애 상황에서 모든 색·막대가 원하는 형태로 바뀌는지 또는 RPO가 0인지 판정하지 않는다.
