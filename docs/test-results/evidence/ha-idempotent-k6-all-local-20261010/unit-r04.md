# 로컬 단위시험 r04 — DB 증감 조건 추가 후 재확인

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-unit-r04`, 2026-10-10 약 17:37 KST |
| 위치·기준 | 로컬 별도 작업 공간, 합성 `run_id=ha777retry` |
| 목적·명령 | 재전송 201 수와 DB 신규 행·ID 증가 수 대조; `PYTHONPATH=scripts/opensql PYTHONPYCACHEPREFIX=<임시 캐시> python3 -m unittest test_retry_idempotent_ha_probe -q` |
| 기준·결과 | **13개 통과·실패 0**, 0.532초, 종료 코드 0 |
| 해석 | `db_rows_added=retry_201_new`와 `db_unique_ids_added=retry_201_new`를 새 최종 판정에 포함한 상태에서 통과 |
| 한계 | 합성 CSV와 합성 HTTP 원장. 실전 DB 증가·k6 요청은 미측정 |
