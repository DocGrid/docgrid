# 로컬 단위시험 r03 — 첫 전체 통과

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-unit-r03`, 2026-10-10 KST (초 단위 시각 미수집) |
| 위치·기준 | 로컬 별도 작업 공간, 합성 `run_id=ha777retry` |
| 목적·명령 | 기존 제한 재전송과 새 전체 ID 재전송 회귀; `PYTHONPATH=scripts/opensql PYTHONPYCACHEPREFIX=<임시 캐시> python3 -m unittest test_retry_idempotent_ha_probe -v` |
| 기준·결과 | **13개 통과·실패 0**, 약 0.04초, 종료 코드 0 |
| 해석 | 전체 원장 ID 동결, 200/201과 DB 행 대조, 원본 201 누락 보존, dropped/누락 감지, manifest 변조 거부를 합성 자료로 확인 |
| 한계 | k6 바이너리·GCP LB·PostgreSQL VM 실전 실행은 포함하지 않음 |
