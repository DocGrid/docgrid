# 정상 HTTP 쓰기 기준선 — `ha448base01`

- 시간: 2026-10-09 **05:20:59~05:22:01 KST**.
- 위치: GCP 내부 부하 VM → 내부 LB → 앱 A/B → OpenProxy A/B → OpenSQL primary.
- 목적/성공 기준: 100 req/s·60초·초기 VU 160에서 미전송 0, 201 응답 전부 DB 1행, 원격 지표 합계 일치.

| 실행 위치·명령/방법 | 결과 요약 | 해석 |
| --- | --- | --- |
| 부하 VM: `run_ha_probe_k6.sh ha448base01 100 60s <내부 LB URL> <보호된 JWT 파일> baseline-write 160` | HTTP **6,001건**, 201 **6,001**, dropped **0**, k6 exit **0** | 설정된 부하를 실제 전송했고 실패 응답은 없었다. |
| 부하 VM: `ha_load_telemetry.py summarize` | 활성 VU 최대 **3**, 안전 이벤트 **12,002**, VM/1초 검증 exit **0/0** | 사전 확보 VU 160과 실제 활성 VU는 다르다. |
| 관측 VM: Remote Write 최종 합계 대조 | 201 **6,001/6,001**, exit **0** | Dashboard의 해당 run_id를 조회할 수 있다. |
| 현재 primary: `export_ha_probe_counts.sh <primary> ha448base01` + 원장 대조 | DB 고유 ID **6,001**, 201 누락 **0**, 중복 **0**, 불명 **0** | 정상 기준선의 요청별 보존 관측. |

p50/p95/p99는 **11.61/52.20/54.39 ms**다. 원장 SHA-256: `e899e03af52ed73e1458960a0c3e57dea63fe0b6f1a1aeb5f448e297993fee1c`. 이 1회 실행은 장애 없는 기준선이며 장기간 SLO나 반복 분포는 증명하지 않는다.
