# HA probe 재전송·원장 재검증

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-idem-replay-20261010-02`, 2026-10-10 11:36 KST |
| 위치·리비전 | 로컬 `scripts/opensql/`, `db4c220` 기준 작업 브랜치의 미커밋 변경 |
| 목적·명령 | 재전송 제한·비식별 원장·대조 도구 회귀; `python3 -m unittest -q test_retry_idempotent_ha_probe test_sanitize_ha_k6_events test_ha_evidence` |
| 성공 기준 | 모든 대상 시험 통과, 실패 0 |
| 관측 | **27건 통과·실패 0건**, 0.223초, 종료 코드 **0** |
| 범위 | 모의 HTTP 및 로컬 루프백 시험이다. GCP VM 장애·실제 LB 경유 재전송 결과가 아니다 |

표준 출력의 시험용 무작위 ID는 원본 증거에 포함하지 않았다. JWT·내부 URL·개인정보는 저장하지 않았다.
