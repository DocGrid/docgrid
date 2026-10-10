# OpenSQL HA 대시보드 단발 오류·장애 시각 표시 검증

- 기록 시각: 2026-10-10 02:12 KST. 일부 진단 재시도의 정확한 시작 시각은 수집하지 못해 아래 실행별 로그에 그 한계를 적었다.
- 변경 범위: Grafana 시연 Dashboard JSON·설명·정적/PromQL 회귀 시험. 백엔드 코드, DB, OpenProxy, k6 부하 스크립트는 변경하지 않았다.
- 기존 원본 실행: `ha1009163528` (30 req/s × 120초, HTTP 201 3,599건·500 1건·결과 불명 0건). 이 문서 작업 중 새 장애나 부하를 실행하지 않았다. 원래 요청 ID 원장과 DB 대조 원본은 비공개 보관하며 이 PR에 복사하지 않는다.

## 변경 전후와 판정

| 항목 | 이전 | 변경 후 실제 확인 | 해석·한계 |
|---|---|---|---|
| 500 표시 | `rate(...[30s])` 결과별 응답/초에 포함 | `last_over_time(...[10m])` 누적 계단에서 기존 실행의 **0→1건** | 실제 첫 Counter 표본은 이미 1이었다. 장애 명령 반환 시각과 그래프 시각은 Remote Write 전송 주기 때문에 다를 수 있다. |
| 결과 불명 | 누적 카드 없음 | 별도 카드 **0건** | `HTTP 2xx~5xx` 카드와 분리. DB 반영 여부를 뜻하지 않는다. |
| 성공 처리율 | 30초 이동 창 | 15초 이동 창 | 더 짧은 관측 창이며 정확한 장애 RTO가 아니다. |
| 앱 5xx | 초당 비율·30초 창 | 15초 증가량 패널 | Prometheus 보간값이라 정확한 1건과 숫자가 같지 않을 수 있다. LB 자체 오류는 빠질 수 있다. |
| 장애 시각 | built-in annotation 조회만 설정 | 원본 기록의 시작·복구 시각 **2건**을 Grafana DB에 기록, 과거 화면에서 세로선 확인 | JSON만 다른 Grafana에 가져오면 이 두 레코드는 함께 복사되지 않는다. |

### 별도 실행 기록

| 실행 ID | 목적·위치 | 결과 | 기록 |
|---|---|---|---|
| `fix457-static-final` | 로컬 JSON/배치/정적 단위 시험 | 12/12 통과, JSON 유효, diff 오류 없음 | [정적 검증](evidence/issue-457/static-final.md) |
| `fix457-promql-attempt-01` | 로컬 Docker 희소 표본 첫 합성 시험 | 2개 기대값 실패 | [1차 진단](evidence/issue-457/promql-attempt-01.md) |
| `fix457-promql-attempt-02` | 평가 시각 조정 재시험 | 2개 기대값 실패 | [2차 진단](evidence/issue-457/promql-attempt-02.md) |
| `fix457-promql-attempt-03` | 원시 입력 시계열 추가 확인 | 3개 기대값 실패 | [3차 진단](evidence/issue-457/promql-attempt-03.md) |
| `fix457-promql-final` | 합성 Counter 한 표본·0 대체·`rate()` 한계 | 최종 12개 PromQL 기대값 통과 | [최종 PromQL](evidence/issue-457/promql-final.md) |
| `fix457-live-query-01` | GCP 관측 VM 내부 Prometheus 과거 실행 읽기 | 원시 500 표본 11개; 새 쿼리 0→1 | [실측 쿼리](evidence/issue-457/live-query.md) |
| `fix457-grafana-01` | 인증된 Grafana 적용·과거 화면 조회 | Dashboard v8→v9, 20패널, annotation 2건, 카드 3,599/0/0/1/0 | [화면 확인](evidence/issue-457/grafana-applied.md) |

초기 PromQL 실패 3회는 제품 장애가 아니다. 합성 Fixture의 맨 앞 누락 표본(`_`)만으로는 이 `promtool` 실행에서 늦게 시작하는 원시 시계열을 만들지 못했다. 세 번째 실행에서 원시 시계열 자체가 없음을 확인하고, 한 표본을 시작 시각에 두는 Fixture로 바꿨다. 이 입력에서는 `rate()`가 값을 내지 못하지만 새 누적 쿼리는 1을 유지한다. 실제 GCP 과거 시계열에서도 새 쿼리의 0→1 변화는 별도로 확인했다.

장애 표식의 원본 시간은 2026-10-10 **01:38:39.554 KST**(종료 명령 반환·포트 불응 확인)와 **01:39:16.246 KST**(프로세스 1·포트 준비 확인)이다. 이 둘의 차이를 앱 쓰기 공백이나 RTO로 부르지 않는다. Grafana annotation 레코드는 서버 DB에 있으며 Dashboard JSON에는 조회 설정만 들어간다. DB 요청 ID 대조는 이번 수정에서 재실행하지 않았으므로 Grafana 그래프만으로 RPO를 주장하지 않는다.
