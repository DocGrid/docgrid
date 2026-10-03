# Primary 미확인 차단 추가 후 재실행

- 실행 ID: `fixture-397-unit-06`
- 기록 시각: 2026-10-03 13:20 KST 이후. 정확한 명령 시작 시각은 수집하지 못함
- 목적: 세 DB 노드 모두 replica로 관측될 때 쓰기·타이머 취소 없이 중단되는지 확인
- 위치·환경: 로컬 macOS, Python 3.14, 가짜 원격 클러스터; 실제 GCP·DB 접속 없음
- 명령: `python3 -m unittest discover -s scripts/opensql -p 'test_ha_fixture_cleanup.py' -v`
- 성공 기준: 추가 사례를 포함한 일곱 시나리오 통과
- 결과: **7/7 통과**, 실패 **0건**, 오류 **0건**, 종료 코드 **0**. 새 사례에서 `remove` 호출 **0건**, `cancel` 호출 **0건**, 가짜 타이머 **3개 유지**.
- 해석: primary를 확인하지 못한 경로의 로컬 안전 판정이다. 실제 Patroni 장애 중 타이머 동작은 아직 미검증이다.
