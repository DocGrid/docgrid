# 로컬 코드·원장 검증 기록

| 목적 | 위치·명령 | 결과 | 해석 |
| --- | --- | --- | --- |
| 이벤트 사전 비식별화 회귀 | 로컬 작업 브랜치 `test/391` · `python3 -m unittest scripts/opensql/test_sanitize_ha_k6_events.py` | **3/3 통과**, 0.001초 | 정상 201쌍은 보존, 예상 밖 URL 필드와 잘못된 성공 코드는 전체 출력 거부 |
| 복구 가드 셸 구문 | 같은 브랜치 · `bash -n scripts/opensql/openproxy_fault_guard.sh` | 종료 코드 **0** | 구문 검사만 통과; 실제 자동 복구는 별도 GCP 리허설로 검증 |
| 목적별 k6 로그 라벨 | 같은 브랜치 · `bash -n scripts/opensql/run_ha_probe_k6.sh` 및 잘못된 목적 코드 호출 | 구문 검사 **0**, 잘못된 목적 코드 **exit 2** | 최초 GCP 실행은 수정 전 runner였음. 후속 `ha391fa20001`·`ha391fb20001`은 수정판을 부하 VM에 설치하고 각 실행 로그에 A/B 목적이 올바르게 출력됨 |
| 외부 원장 재생 | 같은 브랜치 · `ha_evidence.py verify --run-dir <기준선/A 실행별 디렉터리>` | **2/2 통과** | 기준선·A 실행의 파생 `requests.csv`, `summary.json`이 이벤트와 일치 |
| B 외부 원장 후속 완성 | `ha_evidence.py import-k6 → finish → verify`, `reconcile_ha_probe.py --run-dir .../ha391fb100 --db-csv ...` | **26,002개 이벤트**, 요청 13,001개·201 12,991·500 10. 누락·중복·orphan·실패 후 반영 0. 대조 종료 코드 **2** | 앞선 전송 차단을 우회한 것이 아니라 B 전용 목적지·A 해시 불변을 확인한 후 안전 필드만 반출. 2는 HTTP 실패로 정상 기준선 실패 |
| 연결 계측 로컬 회귀 | `python3 -m unittest scripts.opensql.test_sample_ha_app_connections scripts.opensql.test_analyze_ha_proxy_recovery scripts.opensql.test_sanitize_ha_k6_events` | **9/9 통과**, 0.092초 | JVM 소유 소켓·IPv4-mapped TCP6, 샘플 구간과 세 번의 안정 확인, 추가 비밀 열 거부, k6 이벤트 허용 목록 검사 |
| 연결 계측 GCP 사전 시험 1 | 앱 A의 `/proc/<pid>/net/tcp`만 읽는 초기 계측기 10초 실행 | Hikari idle **5**, 프록시 소켓 **0**으로 모순 | **실패한 사전 시험**. Java 소켓이 `tcp6`의 IPv4-mapped 주소라는 원인 확인. 장애 결과에 포함하지 않음 |
| 연결 계측 GCP 사전 시험 2 | 수정 계측기 10초씩 앱 A/B에서 실행 | 앱 A/B 각각 **20/20샘플**, 메트릭 누락 **0**, 프록시 소켓 합계 **5/5** | TCP6 해석 수정 후 장애 실행 전 계측기 관문 통과 |
| 후속 A/B 외부 원장 | 각 실행 `ha_evidence.py verify`, `reconcile_ha_probe.py`, `analyze_ha_proxy_recovery.py` | A **13,000건·500 3**, B **13,001건·500 8**; 201 누락·중복 0, 앱별 샘플 **330/330** | 대조 종료 코드 둘 다 **2**는 HTTP 실패 때문. 별도 연결 분석 JSON은 500ms 표본의 상·하한을 기록 |
| 세 원장 최종 무결성 재검증 | 로컬 `ha_evidence.py verify`를 기존 B·새 A·새 B 각각 수행 | 첫 호출은 관리형 작업트리 파일 잠금 권한으로 **실행 실패**, 허용된 작업트리 쓰기 권한으로 재실행해 **3/3 통과** | 첫 오류를 데이터 불일치로 해석하지 않음. 재시도에서는 이벤트 재생과 파생 summary/requests 일치 확인 |
| 공개 증거 비식별 검사 | 실행별 JSON/Markdown/텍스트 및 gzip 해제 스트림에 대해 내부 IP·프로젝트 ID·개인 경로·비밀값 패턴 검사 | 첫 zsh 실행은 매치되지 않는 `*.txt` glob에서 **실패**, bash로 재실행해 **의심 파일 0** | 비밀값 자체는 출력하지 않았고 검사기 실행 오류와 내용 검사 통과를 구분 |

Java 프로덕션 코드는 이번 브랜치에서 바꾸지 않았으므로 전체 Gradle 회귀는 다시 실행하지 않았다. 원격 A/B는 같은 기존 JAR 해시로 실행됐다. 계측·분석 코드는 GCP 샘플과 로컬 단위 테스트 양쪽으로 확인했으나, 프로세스 KILL 직후 잠깐의 HTTP 500을 제거하는 앱 재시도 로직까지 검증한 것은 아니다.
