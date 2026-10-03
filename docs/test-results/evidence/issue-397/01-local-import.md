# 로컬 시험 호출 방식 실패

- 실행 ID: `fixture-397-import-01`
- 기록 시각: 2026-10-03 13:20 KST. 정확한 명령 시작 시각은 수집하지 못함
- 목적: 새 정리 로직 단위 테스트 로드
- 위치·환경: 로컬 macOS, Python 3.14, 실제 GCP·DB 접속 없음
- 명령: `python3 -m unittest scripts/opensql/test_ha_fixture_cleanup.py -v`
- 결과: 로드 오류 **1건**, 테스트 본문 실행 **0건**, 종료 코드 **1**. 작업 디렉터리에서 모듈 검색 경로에 `scripts/opensql`이 포함되지 않아 `permission_replica_lag` import 실패.
- 해석: 제품 동작 실패가 아니라 호출 방식 오류. 동일 코드를 표준 `discover -s scripts/opensql` 명령으로 재실행했다. 이 실패 실행을 통과로 합산하지 않는다.
