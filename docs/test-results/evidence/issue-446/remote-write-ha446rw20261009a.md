# k6 Remote Write 합성 전송 — `ha446rw20261009a`

- 실제 이벤트 시각: 2026-10-09 **04:33:20.645–04:33:20.646 KST** (2026-10-08 19:33:20 UTC)
- 실행 위치: GCP 내부 부하 VM → GCP 내부 관측 VM.
- 목적: 실제 DB를 쓰지 않고 k6 2.3.0의 JSONL·Prometheus Remote Write 동시 출력과 마지막 카운터 대조를 확인한다.
- 성공 기준: 합성 `sent`·`acknowledged` **각 1건**, Prometheus `201` Counter **1건**, 나머지 **0건**, JSONL 태그 **0건**, 대조 일치.

| 순서·방법 | 관측 결과 | 해석 |
| --- | --- | --- |
| 합성 k6 JS에서 태그 없는 `ha_outcome_201` Counter 1회와 안전 이벤트 두 줄 생성, `--out json=... --out experimental-prometheus-rw` 동시 실행 | k6 종료 코드 **0**, 이벤트 **2건**, JSONL 지표 Point **5건** | 실제 HTTP·DB 쓰기는 실행하지 않은 전송 경로 시험이다. |
| `K6_PROMETHEUS_RW_LABELS=run_id=ha446rw20261009a`, `K6_PROMETHEUS_RW_STALE_MARKERS=true` | JSONL에서 태그가 붙은 Point **0건**, `ha_outcome_201` Point **1건** | Remote Write 전용 라벨이 기존 JSONL 증거의 태그 금지 규칙에 섞이지 않았다. |
| `HA_PROM_RW_URL=<관측 VM 사설 주소>/api/v1/write python3 verify_ha_prometheus_rw.py verify --events ... --run-id ha446rw20261009a` | 예상 `201=1`, 관측 `201=1`, `500=0`, `503=0`, 기타 실패=0, 불명=0. **첫 조회에서 일치** | 마지막 Prometheus 카운터와 클라이언트 원장 합계가 같다. 중간 전송 시각의 공백은 이 검증으로 판정할 수 없다. |

안전한 원본 이벤트·지표·대조 JSON은 부하 VM의 사용자 전용 `ha-observer-446/` 폴더에 실행 ID별 파일로 남겼다. 저장소에는 내부 주소가 들어갈 수 있는 원본 지표 파일을 넣지 않았다. **실제 HA probe HTTP 쓰기, 실패율·지연, 요청 ID의 DB 대조, 장애 전환은 이번 합성 시험에서 미실행**이다.
