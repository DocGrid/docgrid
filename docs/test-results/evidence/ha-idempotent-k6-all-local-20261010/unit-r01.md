# 로컬 단위시험 r01 — 모듈 검색 경로 누락

| 항목 | 기록 |
| --- | --- |
| 실행 ID·시각 | `local-ha-all-unit-r01`, 2026-10-10 KST (초 단위 시각 미수집) |
| 위치·기준 | 로컬 별도 작업 공간, `origin/develop` `61515ea238f4e5f45606c1598f1d1e21af6efadc` |
| 목적·명령 | 전체 ID 목록/DB 대조 회귀; `python3 -m unittest scripts/opensql/test_retry_idempotent_ha_probe.py -v` |
| 기준·결과 | 13개 통과 목표. **0개 실행**, import 오류 1개, 종료 코드 1 |
| 해석 | Python 모듈 검색 경로가 누락됐다. 코드 동작 판정 불가. 다음 실행에서 `PYTHONPATH=scripts/opensql` 지정 |
| 정보 보호 | 실패 출력의 로컬 경로와 환경 식별자는 이 기록에 복사하지 않음 |
