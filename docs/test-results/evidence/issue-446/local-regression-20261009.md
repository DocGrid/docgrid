# k6 계측 코드 로컬 회귀 — 2026-10-09

- 실행 ID: `ha446local20261009a`
- 기록 시각: 2026-10-09 04:38 KST
- 목적: JSONL 안전 게이트를 유지하면서 결과별 Counter와 원격 최종 합계 대조기를 추가한다.
- 실행 위치: 최신 `develop`에서 만든 독립 `test/446` 작업트리. 기존 작업 폴더의 수정·미추적 파일은 건드리지 않았다.
- 성공 기준: 관련 Python suite **17/17**, Bash·JS 구문 검사, diff whitespace 검사 통과. 원격 DB 장애 성능은 이 로컬 시험의 대상이 아니다.

| 순서·명령 | 결과 요약 | 해석 |
| --- | --- | --- |
| 초기 `python3 -m unittest scripts/opensql/test_verify_ha_prometheus_rw.py scripts/opensql/test_ha_load_telemetry.py` | **9개 탐색 중 1개 import 오류**, 나머지 8개 통과 | 테스트 코드 실패가 아니라 루트 기준 직접 모듈명 호출에서 `scripts/opensql`이 import 경로에 없었다. 실패 시도를 지우지 않는다. |
| `python3 -m unittest discover -s scripts/opensql -p 'test_verify_ha_prometheus_rw.py'` | **6/6 통과** | 원장 이벤트 검증, 502 보존, 사설 수신 주소, 마지막 Counter, 누락 판정 확인. 네트워크는 mock이다. |
| `python3 -m unittest discover -s scripts/opensql -p 'test_ha_load_telemetry.py'` | **8/8 통과** | 새 태그 없는 Counter가 기존 JSONL 요약을 깨지 않음. 태그가 붙은 임의 지표를 허용하도록 게이트를 완화하지 않았다. |
| `python3 -m unittest discover -s scripts/opensql -p 'test_sanitize_ha_k6_events.py'` | **3/3 통과** | 원장 안전 이벤트 필드 검증의 기존 회귀 조건을 유지했다. |
| `bash -n scripts/opensql/run_ha_probe_k6.sh` | 종료 코드 **0** | 셸 구문 확인. 실제 부하 실행은 아니다. |
| `node --check scripts/opensql/ha_probe_load.js` | 종료 코드 **0** | JavaScript 구문 확인. k6 런타임 검사는 별도 수행. |
| `git diff --check` | 종료 코드 **0** | 변경 파일의 공백 오류 없음. |
| 부하 VM k6 2.3.0 `k6 inspect` 첫 시도 | 종료 코드 **107**, 필수 `HA_*` 값 없음 | `inspect`는 전달한 프로세스 환경 변수를 스크립트에 넣지 않았다. HTTP 요청은 발생하지 않았다. |
| 부하 VM `k6 inspect -e HA_RUN_ID=... -e HA_TARGET_URL=... -e HA_JWT=dummy -e HA_SUMMARY_FILE=... ha_probe_load.js` | 종료 코드 **0**, 구조 JSON 생성 | 합성값으로 실제 설치된 k6 런타임 파싱을 확인. 토큰이나 HTTP 요청을 사용하지 않았다. |

현재 부하 VM에서 읽은 기존 후보 JWT 파일 **1개는 만료 전·0600 조건을 충족하지 않았다**. 따라서 수정된 전체 HA probe를 실제 앱 쓰기로 재실행하지 않았다. 원격 전송 자체는 [별도 합성 시험](remote-write-ha446rw20261009a.md)에서 확인했다.
