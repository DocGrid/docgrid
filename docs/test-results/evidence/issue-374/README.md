# WebSocket 대시보드 권한 검증 부하 시험 원본 목록

관련 이슈: [#374](https://github.com/DocGrid/docgrid/issues/374) · [해석 보고서](../../gimin-374-stomp-dashboard-outbound-load-20261001.md)

2026-10-01 GCP 내부 시험에서 수집한 클라이언트 결과 JSON 12개와 앱 메트릭 JSONL 10개를 **시험 목적·버전·구독자 수·회차별 별도 파일**로 보존한다. `base`는 보안 수정 전 커밋 `f6213bbdb6f487af00727be156da0710d619cc6a`, `fix`는 #373의 커밋 `b425c3535194be6719b75ef79cf5100d9a86be97`이다. 접미사 없는 50개 파일이 1회차이며 `r2`, `r3`가 2·3회차다.

| 목적·회차 | 수신 결과 | 앱 메트릭 | 관측 창 | 수신 건수 | p95 |
| --- | --- | --- | ---: | ---: | ---: |
| 기존 1명, 스모크 | [`base-n1.json`](runs/base-n1.json) | 없음 | 12초 | 40 | 2.874 ms |
| 수정 1명, 스모크 | [`fix-n1.json`](runs/fix-n1.json) | 없음 | 12초 | 40 | 55.995 ms |
| 기존 5명 | [`base-n5.json`](runs/base-n5.json) | [`base-n5-metrics.jsonl`](metrics/base-n5-metrics.jsonl) | 30초 | 500 | 3.637 ms |
| 수정 5명 | [`fix-n5.json`](runs/fix-n5.json) | [`fix-n5-metrics.jsonl`](metrics/fix-n5-metrics.jsonl) | 30초 | 500 | 217.529 ms |
| 기존 20명 | [`base-n20.json`](runs/base-n20.json) | [`base-n20-metrics.jsonl`](metrics/base-n20-metrics.jsonl) | 30초 | 2,000 | 5.180 ms |
| 수정 20명 | [`fix-n20.json`](runs/fix-n20.json) | [`fix-n20-metrics.jsonl`](metrics/fix-n20-metrics.jsonl) | 30초 | 599 | 969.252 ms |
| 기존 50명, 1회 | [`base-n50.json`](runs/base-n50.json) | [`base-n50-metrics.jsonl`](metrics/base-n50-metrics.jsonl) | 30초 | 5,000 | 9.117 ms |
| 기존 50명, 2회 | [`base-n50-r2.json`](runs/base-n50-r2.json) | [`base-n50-r2-metrics.jsonl`](metrics/base-n50-r2-metrics.jsonl) | 30초 | 5,000 | 8.475 ms |
| 기존 50명, 3회 | [`base-n50-r3.json`](runs/base-n50-r3.json) | [`base-n50-r3-metrics.jsonl`](metrics/base-n50-r3-metrics.jsonl) | 30초 | 5,000 | 8.011 ms |
| 수정 50명, 1회 | [`fix-n50.json`](runs/fix-n50.json) | [`fix-n50-metrics.jsonl`](metrics/fix-n50-metrics.jsonl) | 30초 | 603 | 2,386.041 ms |
| 수정 50명, 2회 | [`fix-n50-r2.json`](runs/fix-n50-r2.json) | [`fix-n50-r2-metrics.jsonl`](metrics/fix-n50-r2-metrics.jsonl) | 30초 | 588 | 2,451.737 ms |
| 수정 50명, 3회 | [`fix-n50-r3.json`](runs/fix-n50-r3.json) | [`fix-n50-r3-metrics.jsonl`](metrics/fix-n50-r3-metrics.jsonl) | 30초 | 588 | 2,443.748 ms |

## 읽는 방법과 한계

- 결과 JSON의 `frames_received`는 관측 창에서 **전체 수신자가 받은 프레임 합계**다. `interior_sequence_gaps=0`은 받은 첫·마지막 sequence 사이에 구멍이 없다는 뜻일 뿐, 예정된 주기의 push가 모두 생성됐다는 뜻이 아니다.
- `latency_ms`는 임시 publisher의 `sentEpochMillis`와 부하 VM 수신 시각의 차이다. 두 VM을 같은 NTP 기준으로 동기화한 후의 결과만 이 디렉터리에 넣었다. 앞선 시간 동기화 전 음수 지연 2회는 제외했다.
- 메트릭 JSONL의 `at`은 UTC다. 보고서의 사람이 읽는 로그 시각은 KST다. 앱 지표 채집 창은 클라이언트 관측 창보다 길어 백그라운드 작업도 포함한다. 메트릭에서 메시지당 SQL 수를 직접 계산하지 않는다.
- 클라이언트 결과 JSON에는 절대 실행 시각이나 UUID run ID가 없다. **파일명과 이 목록**으로 회차를 매핑한다. 1명 스모크에는 앱 메트릭 파일이 없고, 1·5·20명은 반복 실행이 없어 신뢰구간을 주장하지 않는다.
- DB snapshot 원본에는 내부 주소가 포함되어 공개하지 않는다. JVM 덤프 원본은 이 저장소에 보존되지 않았다. 보고서는 당시 비식별 관찰만 적으며, 이 두 근거는 공개 원본만으로 독립 재검증할 수 없다.
- [`websocket_dashboard_load.py`](../../../../scripts/opensql/websocket_dashboard_load.py)와 [`websocket_dashboard_metrics.py`](../../../../scripts/opensql/websocket_dashboard_metrics.py)는 실행 당시 스크립트의 측정 로직을 보존하되, 추후 실패 시 예외 문자열에 내부 URL이 기록되지 않도록 **예외 클래스명만 저장하는 비식별 수정**을 했다. 임시 Java `wsbench` publisher·시험용 ADMIN fixture는 저장소에 포함하지 않으므로 이 두 스크립트만으로 당시 합성 부하를 끝까지 재현할 수는 없다.

## 공개 범위

원본 JSON/JSONL에는 토큰·암호·계정·내부 IP·프로젝트 ID를 넣지 않았다. 별도 journal에서 필요한 기동·경고·종료 항목은 보고서에 기록 전에 비식별화된 한국어 표로만 옮겼다. 공개 GitHub에 보관하는 것은 이 선별 결과이며, 원래 VM의 모든 로그를 보존했다는 뜻은 아니다.
