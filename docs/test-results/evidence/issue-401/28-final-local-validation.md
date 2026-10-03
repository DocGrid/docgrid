# #401 공개 PR 직전 로컬 검증

- 실행 ID: `issue-401-final-local-01`
- 목적: 실제 GCP에 설치된 비식별 도우미와 동일한 소스를 PR로 올리기 전에 단위 시험·문법·공백·비밀 패턴을 확인한다.
- 실행 위치: 개발자 로컬 `fix/401` 작업 트리.
- 명령: `PYTHONPYCACHEPREFIX=<임시 캐시> python3 -m unittest discover -s scripts/opensql -p 'test_ha_node_container.py' -v`; 동일 캐시로 `py_compile`; staged diff `--check`; 새 코드·증거의 hostname/IP/프로젝트 ID/로컬 경로 패턴 검색.
- 성공 기준: 단위 시험 6/6, 문법·공백 오류 0건, 비밀/내부 식별자 패턴 0건.
- 결과: Python 단위 시험 **6/6 통과**(0.001초), 문법 오류 **0건**, 새 코드·증거·요약 보고서의 실제 hostname/내부 IP/프로젝트 ID/로컬 절대 경로/비밀 패턴 검색 **0건**(검색 명령 종료 코드 1은 매치 없음). 프로덕션·테스트 각 명시적 stage 직후 `git diff --cached --check` 공백 오류 **0건**.
