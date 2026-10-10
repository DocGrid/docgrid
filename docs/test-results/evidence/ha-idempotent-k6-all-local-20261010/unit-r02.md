# 로컬 단위시험 r02 — 로컬 포트 권한 제한

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-unit-r02`, 2026-10-10 KST (초 단위 시각 미수집) |
| 위치·기준 | 로컬 별도 작업 공간, 합성 `run_id=ha777retry` |
| 목적·명령 | 같은 13개 회귀; `PYTHONPATH=scripts/opensql python3 -m unittest test_retry_idempotent_ha_probe -v` |
| 기준·결과 | **12개 통과·1개 환경 오류**, 종료 코드 1. 새 전체 ID 모드 4개는 모두 통과 |
| 해석 | 기존 HTTP 리다이렉트 시험이 로컬 `127.0.0.1` 임시 포트를 열 때 샌드박스가 거부했다. 요청/응답 동작 결함으로 판정하지 않음 |
| 다음 실행 | 승인된 localhost 포트 실행 권한에서 동일 시험 재실행 |
