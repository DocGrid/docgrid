# GCP BGE 수집·접근 경계 — `rag479-gcp-scrape-r1`

- 목적: BGE 임베딩 API는 앱에 계속 열어 두면서 `/metrics`는 관측 VM만 읽게 하고, Prometheus/Grafana가 실제 수집하는지 확인한다.
- 실행 위치·시각: GCP 앱 A, BGE CPU VM, 관측 VM. 2026-10-10 03:37~03:56 UTC.
- 성공 기준: 앱→BGE `/health` 200·`/metrics` 403, 관측 VM→`/metrics` 200, Prometheus `up=1`, 실임베딩 후 요청 수 증가, Grafana 자료원 경유 값 일치.

| 절차·명령 요약 | 결과 | 해석 |
| --- | --- | --- |
| BGE 앱에 `EMBEDDING_METRICS_ALLOWED_CLIENTS`를 관측 VM으로 지정한 이미지를 적용하고 컨테이너 readiness 확인 | `/health` **200**, BGE 컨테이너 정상 | 임베딩 경로 유지 |
| 앱 A에서 BGE `/health`와 `/metrics` 각각 HTTP 상태 조회 | **200 / 403** | 앱은 임베딩 가능하지만 지표 본문은 차단 |
| 관측 VM에서 BGE `/metrics` 조회 | 최초 네트워크 **타임아웃**; 관측 태그에서 BGE 포트로 가는 단일 방화벽 허용 추가 후 **200** | BGE의 기존 ingress deny를 확인하고 허용 출처만 좁게 추가 |
| 기존 Prometheus config 백업, `promtool check config`, 재시작 | 첫 검사는 비root 컨테이너가 0600 임시 파일을 읽지 못해 실패; root 검사 재실행 **통과** | 본체 문법 문제가 아닌 검증 파일 권한 문제 |
| Prometheus `up{job="embedding-provider"}`와 요청 Counter 조회 | `up` **1/1**, 실제 PDF 인덱싱·검색 뒤 BGE 요청 **2건** | 대상이 단순 등록만 된 것이 아니라 실제 작업 지표가 증가 |
| Grafana 인증 API의 `ha-prometheus` 자료원 `/api/ds/query`로 같은 PromQL 조회 | BGE `up=1`, 요청 **2건** | Prometheus 값과 Grafana 경유 값 일치 |

BGE `/metrics`와 `/embed`는 동일 TCP 포트다. 방화벽만으로 URL 경로를 분리했다고 주장하지 않는다. 방화벽은 관측 VM의 포트 접근만 추가했고, 애플리케이션의 클라이언트 주소 검사가 `/metrics`를 제한한다. 관측 종료 후 BGE VM을 원래의 `TERMINATED` 상태로 되돌렸으므로 현재 시점의 `up=1`을 보장하지 않는다. 이 문서의 값은 시험 중 수집한 시계열이다.

