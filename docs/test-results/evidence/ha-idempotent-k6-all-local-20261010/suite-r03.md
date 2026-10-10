# 전체 OpenSQL 로컬 회귀 r03 — JWT 유효시간 관문 포함

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-suite-r03`, 2026-10-10 약 17:39 KST |
| 위치·기준 | 로컬 별도 작업 공간, 합성 `run_id=ha777retry` |
| 목적·명령 | JWT 남은 시간 검사와 기존 도구 영향 확인; `PYTHONPATH=scripts/opensql PYTHONPYCACHEPREFIX=<임시 캐시> python3 -m unittest discover -s scripts/opensql -p 'test_*.py' -q` |
| 성공 기준·관측 | 전체 통과; **149개 통과·실패 0**, 3.735초, 종료 코드 0 |
| 해석 | 만료 임박·잘못된 JWT는 재전송 전에 차단하는 합성 시험 포함. 원본 제한적 재전송 시험도 유지 |
| 한계 | JWT 서명·역할 검증은 백엔드 책임. 실제 k6 바이너리, GCP LB/DB 장애는 이 시험에 없음 |
