# OpenSQL Python 전체 회귀 — ha412-local-suite-01

- 목적: 파서 수정 직후 OpenSQL 시험 스크립트 전체 회귀 확인.
- 위치: 로컬 개발 머신 Python 3.14.6.
- 명령: `python3 -m unittest discover -s scripts/opensql -p 'test_*.py' && bash -n scripts/opensql/run_ha_probe_k6.sh && git diff --check`.
- 성공 기준: 테스트·Bash 구문·공백 검사 모두 종료 코드 0.
- 결과: **84/84 통과**, 구문·diff 검사 통과. 이 시점은 선택적 초기 VU 인자 변경 전이므로 [최종 재실행](local-suite-02.md)을 따로 남긴다.
