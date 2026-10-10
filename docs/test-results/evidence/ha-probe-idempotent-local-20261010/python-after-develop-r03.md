# 최신 develop 병합 후 HA probe 재전송·원장

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-idem-replay-20261010-03`, 2026-10-10 11:37 KST |
| 환경·리비전 | 로컬 `scripts/opensql/`, `origin/develop`의 `105d6d6`을 병합한 `9b92eca` |
| 목적·명령 | 재전송 대상·증거 검증 회귀; `python3 -m unittest -q test_retry_idempotent_ha_probe test_sanitize_ha_k6_events test_ha_evidence` |
| 성공 기준·관측 | 대상 시험 전부 통과; **27건 통과·실패 0**, 0.748초, 종료 코드 **0** |
| 한계 | 실제 GCP LB·DB 장애나 앱 A/B 배포를 포함하지 않음 |
