# HA 쓰기 연결 확인 — `ha448smoke01`

- 시간: 2026-10-09 **05:18:58~05:19:04 KST**.
- 위치: GCP 내부 부하 VM → 내부 LB → 앱 A/B → OpenProxy A/B → OpenSQL primary.
- 목적/성공 기준: 장애 전 짧은 실제 HTTP 쓰기 6건이 201이고, 드롭 0·원장↔DB 불일치 0인지 확인한다.

| 실행 위치·명령/방법 | 결과 요약 | 해석 |
| --- | --- | --- |
| 부하 VM: `run_ha_probe_k6.sh ha448smoke01 1 5s <내부 LB URL> <보호된 JWT 파일> baseline-write 160` | HTTP **6건**, 201 **6**, dropped **0**, k6 exit **0** | 내부 앱 쓰기 경로가 작동한다. |
| 부하 VM: `ha_load_telemetry.py summarize` | VM 표본기·1초 계측 exit **0/0**, 안전 이벤트 **12건** | 보낸 이벤트 6 + 완료 이벤트 6을 별도로 남겼다. |
| 관측 VM: `verify_ha_prometheus_rw.py verify` | 201 Counter **6/6**, 검증 exit **0** | 최종 누적 합계 일치. 중간 전송 공백은 별도 미검증. |
| 현재 primary: `export_ha_probe_counts.sh <primary> ha448smoke01` + 원장 대조 | DB 고유 ID **6**, 201 누락 **0**, 중복 **0**, 예상 밖 ID **0** | 응답과 최종 DB 행이 일치한다. |

p50/p95/p99는 **60.75/91.96/99.17 ms**다. 실행 원장 `k6-events.jsonl`의 SHA-256: `a94bf19603bd3915daafbea1769c43614b74799a69789ed5508c6baab52f0726`. 5초 smoke는 성능 기준선으로 사용하지 않는다.
