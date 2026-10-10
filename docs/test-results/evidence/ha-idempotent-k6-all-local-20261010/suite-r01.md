# 전체 OpenSQL 로컬 회귀 r01

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-suite-r01`, 2026-10-10 약 17:34 KST |
| 위치·기준 | 로컬 별도 작업 공간, 원본 코드 + 새 전체 ID 모드 |
| 목적·명령 | 기존 OpenSQL 도구 영향 확인; `PYTHONPATH=scripts/opensql PYTHONPYCACHEPREFIX=<임시 캐시> python3 -m unittest discover -s scripts/opensql -p 'test_*.py' -q` |
| 기준·결과 | **148개 통과·실패 0**, 3.253초, 종료 코드 0 |
| 해석 | DB 증가 필드 추가 전 기준의 전체 회귀 통과. 최종 코드에서 다시 실행 필요 |
| 한계 | 합성 로컬 시험. GCP 장애·k6 바이너리·실제 DB 재전송은 미실행 |
