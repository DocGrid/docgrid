# 로컬 코드·원장 검증 기록

| 목적 | 위치·명령 | 결과 | 해석 |
| --- | --- | --- | --- |
| 이벤트 사전 비식별화 회귀 | 로컬 작업 브랜치 `test/391` · `python3 -m unittest scripts/opensql/test_sanitize_ha_k6_events.py` | **3/3 통과**, 0.001초 | 정상 201쌍은 보존, 예상 밖 URL 필드와 잘못된 성공 코드는 전체 출력 거부 |
| 복구 가드 셸 구문 | 같은 브랜치 · `bash -n scripts/opensql/openproxy_fault_guard.sh` | 종료 코드 **0** | 구문 검사만 통과; 실제 자동 복구는 별도 GCP 리허설로 검증 |
| 목적별 k6 로그 라벨 | 같은 브랜치 · `bash -n scripts/opensql/run_ha_probe_k6.sh` 및 잘못된 목적 코드 호출 | 구문 검사 **0**, 잘못된 목적 코드 **exit 2** | 수정판은 다음 실행에서 목적 라벨을 선택할 수 있음. 이번 GCP 실행은 수정 전 runner였으므로 런타임 적용 결과로 세지 않음 |
| 외부 원장 재생 | 같은 브랜치 · `ha_evidence.py verify --run-dir <기준선/A 실행별 디렉터리>` | **2/2 통과** | 기준선·A 실행의 파생 `requests.csv`, `summary.json`이 이벤트와 일치 |
| B 외부 원장 | 실행별 독립 원장 | **미완료** | 안전 검토에서 비식별 이벤트 전송이 차단돼 ID별 대조 미수행. 성공으로 세지 않음 |

Java 프로덕션 코드는 이번 브랜치에서 바꾸지 않았으므로 전체 Gradle 회귀는 다시 실행하지 않았다. 원격 A/B는 같은 기존 JAR 해시로 실행됐다.
