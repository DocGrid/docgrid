# fix382-harness-r8 — A/B 프레임 수 계측 단위 시험

- 시각: 2026-10-02 KST, 시작 초 단위는 기록하지 못함.
- 위치/기준: 로컬 Python, 테스트 커밋 `80488ac`.
- 목적·성공 기준: 기존 합성 지연 측정을 유지하면서 실제 DB 요약용 count-only가 `searchable`을 발행 시각으로 오인하지 않음.
- 실행: `python3 -B -m unittest discover -s scripts/opensql -p 'test_websocket_dashboard_revocation.py' -v`.
- 관측: **5/5 통과**, 실패 0. 새 테스트에서 실제 요약 `total=12`, `searchable=8`은 count-only 모드에서 시퀀스·발행 시각으로 사용되지 않았다.
- 해석: 파서 분기 시험이며 실제 GCP 프레임 수는 아직 측정하지 않았다.
- 정리: 테스트가 외부 자원이나 비밀 파일을 만들지 않았다.
