# 최종 OpenSQL Python 전체 회귀 — ha412-local-suite-02

- 목적: 선택적 초기 VU 인자와 파서 수정이 함께 있는 최종 상태 회귀 확인.
- 위치: 로컬 개발 머신 Python 3.14.6.
- 명령: `python3 -m unittest discover -s scripts/opensql -p 'test_*.py' && bash -n scripts/opensql/run_ha_probe_k6.sh && git diff --check`.
- 성공 기준: 테스트·Bash 구문·diff 모두 종료 코드 0.
- 결과: **84/84 통과**, 실패·건너뜀 0, 구문·diff 검사 통과. GCP 실측 결과와는 별도 로컬 코드 검사다.
