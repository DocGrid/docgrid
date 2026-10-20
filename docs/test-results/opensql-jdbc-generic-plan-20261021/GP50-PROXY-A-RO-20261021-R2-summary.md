# GP50-PROXY-A-RO-20261021-R2 결과

- 실제 표본 구간: 2026-10-21T08:07:05.003513+09:00 ~ 2026-10-21T08:11:42.198558+09:00
- 기존 BGE 캐시·Seed 545·50,008청크·3,559문서·D01·허용100%·후보40·auto·동시성1
- [원시 이벤트](GP50-PROXY-A-RO-20261021-R2-events.jsonl) · [호출별 카운터 증가량](GP50-PROXY-A-RO-20261021-R2-classified.json)

| SQL | 전체 n / p50(ms) | Custom n / p50(ms) | Generic n / p50(ms) | 최초 Generic 회차 |
|---|---:|---:|---:|---:|
| 수정 전 | 30 / 9695.66 | 14 / 1022.62 | 16 / 9755.10 | 12 |
| 수정 후 | 30 / 1009.72 | 14 / 1023.68 | 16 / 977.76 | 11 |

JDBC 시간에는 IAP·SSH·Proxy 왕복·실행·결과 수신이 포함됩니다. 메타데이터 점검·COMMIT은 표본 타이머 밖입니다. HTTP 사용자 지연으로 쓰지 않습니다.

같은 질문 1개를 반복했습니다. 기존 질문 30개 전체의 서비스 p50과 다른 표본 구성입니다. 별도 EXPLAIN은 SQL별 1회이며 반복 지연 표본에 합산하지 않습니다.

호출별 Generic/Custom 분류는 같은 backend·준비문 별칭의 증가량이 정확히 1인 경우에만 부여합니다. 미분류는 별도 보존합니다.

실제 세션 설정:

```json
{
  "server_version": "17.8",
  "maintenance_work_mem": "64MB",
  "work_mem": "4MB",
  "shared_buffers": "128MB",
  "effective_cache_size": "4GB",
  "random_page_cost": "4",
  "max_parallel_workers_per_gather": "2",
  "default_statistics_target": "100",
  "jit": "on",
  "hnsw.ef_search": "40",
  "hnsw.iterative_scan": "off"
}
```
