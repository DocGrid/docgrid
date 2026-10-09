# OpenProxy 포트 관측·상단 패널 축소 실측 — ha-dashboard-proxy-20261009-01

| 항목 | 기록 |
| --- | --- |
| 실행 시각 | 2026-10-09 14:40~14:48 KST |
| 목적 | OpenProxy A/B 포트의 관측 VM 기준 TCP 연결을 Grafana에 표시하고, primary·timeline·앱·DB 상태 패널을 축소 |
| 실행 위치 | 로컬 검증, GCP 내부 관측 VM의 Prometheus·Grafana·Blackbox Exporter, 기존 앱 2대·DB 3대 |
| 코드 기준 | `test/448`의 `c794914` 기반 미커밋 변경. 이번 확인은 PR·배포 JAR 변경이 아님 |
| 방법 | 로컬 Dashboard 구조 검사 → 관측 VM에서 두 프록시 포트 TCP 확인 → TCP 전용 Exporter 실행 → Prometheus 설정 문법 검사·재적용 → `up`·`probe_success` 쿼리 → Grafana API·실제 화면 확인 |
| 통과 기준 | 앱 2/2·Patroni 3/3·프록시 scrape 2/2, `probe_success` A/B 모두 1, 호스트 포트 비공개, Grafana 14패널·상단 높이 4 이하, 임시 접근 키 제거 |

## 실행 결과

| 확인 | 관측 결과 | 해석 |
| --- | --- | --- |
| 로컬 구조 검사 | `python3 -m unittest monitoring/grafana/tests/test_ha_dashboard.py -v`: **10/10 통과**; JSON 파싱·`git diff --check` 통과 | 패널 ID·배치·쿼리·의미 검사를 통과 |
| TCP 접속 사전 점검 | 관측 VM→OpenProxy A/B 포트 **2/2 연결 성공** | 기존 네트워크 규칙으로 접근 가능해 **새 방화벽 규칙은 만들지 않음** |
| 새 수집 경로 | Prometheus 설정 `promtool check config` 통과, Blackbox Exporter 실행, scrape **2/2 up** | 관측 VM의 TCP probe가 주기적으로 실행됨 |
| 전체 수집 대상 | 앱 **2/2**, Patroni **3/3**, OpenProxy TCP **2/2** | 해당 조회 시점의 7개 target 모두 수집 중 |
| 실제 포트 결과 | `probe_success`: proxy-A **1**, proxy-B **1** | 관측 VM에서 두 TCP 포트에 접속 가능. **SQL 성공을 뜻하지 않음** |
| 외부 노출 | Exporter `9115/tcp`의 호스트 바인딩 **없음** | 관측 VM의 내부 Docker 네트워크에서만 Prometheus가 접근 |
| Grafana 화면 | 기존 UID 유지, 버전 **3→4**, 패널 **12→14**, 프록시 연결 수 **2**, A/B 녹색 막대 2행 | 로그인된 실제 화면에서 신규 패널·축소된 카드 및 상태 타임라인 렌더링 확인 |
| 기존 상태 | 현재 primary **db-node3**, 해당 timeline **14** | 화면 확인 시점의 표시값이며 장애 시험 결과 아님 |
| 정리 | 임시 OS Login 공개키 제거·로컬 개인키 삭제·원격 전송 임시 파일 삭제 | 운영 설정과 롤백용 보호 백업은 유지 |

## 실패·재시도와 한계

- 로컬 YAML 검사 첫 시도는 `PyYAML` 미설치로 실패했다. 같은 설정을 Ruby YAML 파서로 확인했고, 최종 Prometheus 설정은 실제 VM의 `promtool` 검사에 통과했다.
- 첫 `promtool` 실행은 0600 임시 파일을 비특권 컨테이너가 읽지 못해 실패했다. 파일 권한을 낮추지 않고 일회성 검증 컨테이너만 root로 재실행하여 통과했다.
- Exporter 실행과 포트 바인딩 확인에서 원격 명령 인용 오류가 각각 1회 있었다. 오류 명령은 설정을 변경하지 않았고 수정 후 성공했다.
- 실제 OpenProxy 중단·복구 장애는 이번 실행에서 주입하지 않았다. 빨간색 전환, 앱→프록시 실제 사용 경로, SQL 성공, HTTP 부하·DB 정합성은 이 결과로 검증되지 않는다.
- k6 패널은 별도 부하 실행이 없으면 빈 상태가 정상이다. 이 화면의 녹색 포트 막대만으로 가용성·RPO를 주장하지 않는다.
