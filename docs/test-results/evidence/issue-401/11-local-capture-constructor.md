# #401 로컬 캡처·재생성 안전장치 재시험

- 실행 ID: `issue-401-local-capture-02`
- 목적: 기본 이미지에서 빠지는 21개 패키지를 보존하도록 바꾼 `capture → create → verify` 계약을 검증한다.
- 실행 위치: 개발자 로컬의 `fix/401` 작업 트리.
- 명령: `python3 -m unittest discover -s scripts/opensql -p 'test_ha_node_container.py' -v`; `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-issue401-pycache python3 -m py_compile ...`; `git diff --check`.
- 성공 기준: 단위 시험 6건, 문법 검사와 diff 검사가 모두 통과. 실제 GCP·Docker 변경 0건.
- 결과: 단위 시험 **6/6 통과**(0.002초), 문법 오류 **0건**, diff 공백 오류 **0건**. 실제 GCP·Docker 변경 **0건**.
- 판정: 로컬 안전장치 통과. 이미지 캡처와 DB 재합류의 실환경 성공은 아직 주장하지 않는다.
