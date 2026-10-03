# #401 로컬 컨테이너 생성·검증 안전장치 시험

- 실행 ID: `issue-401-local-constructor-01`
- 목적: DB에 접속하지 않고 `--init` 생성 명령과 기존 컨테이너·사용 중인 데이터 디렉터리 거부 조건을 검증한다.
- 실행 위치: 개발자 로컬의 `fix/401` 작업 트리
- 대상: `scripts/opensql/ha_node_container.py`, `scripts/opensql/test_ha_node_container.py`
- 명령: `python3 -m unittest discover -s scripts/opensql -p 'test_ha_node_container.py' -v`; `python3 -m py_compile scripts/opensql/ha_node_container.py`; `git diff --check`
- 성공 기준: 단위 시험 4건 통과, 문법 오류 0건, diff 공백 오류 0건. GCP/Docker 변경은 0건.
- 실행 시각: 2026-10-03 18:44 KST
- 결과: 단위 시험 4/4 통과(0.001초), `git diff --check` 공백 오류 0건, GCP/Docker 변경 0건.
- 재시도: 기본 `py_compile`은 작업 트리의 `__pycache__` 쓰기 권한 부족으로 실패했다. `PYTHONPYCACHEPREFIX=/private/tmp/docgrid-issue401-pycache`를 지정한 동일 문법 검사는 오류 0건으로 통과했다. 첫 실패는 코드 문법 오류가 아니다.
- 판정: 로컬 안전장치 통과. 실제 Docker `--init` 기동·Patroni 재합류는 아직 검증하지 않았다.
