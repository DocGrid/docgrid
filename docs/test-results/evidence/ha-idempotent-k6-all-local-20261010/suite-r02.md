# 전체 OpenSQL 로컬 회귀 r02 — DB 증감 검증 추가 후

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-suite-r02`, 2026-10-10 약 17:37 KST |
| 위치·기준 | 로컬 별도 작업 공간, DB 증가 필드까지 포함한 최종 코드 |
| 목적·명령 | 기존 OpenSQL 도구 영향 확인; `PYTHONPATH=scripts/opensql PYTHONPYCACHEPREFIX=<임시 캐시> python3 -m unittest discover -s scripts/opensql -p 'test_*.py' -q` |
| 기준·결과 | **148개 통과·실패 0**, 3.861초, 종료 코드 0 |
| 해석 | 제한적 재전송 모드와 새 전체 ID 모드를 포함한 Python 로컬 회귀 통과 |
| 한계 | 실제 k6 바이너리·GCP LB·PostgreSQL 장애 시험은 이 작업에서 하지 않음 |
