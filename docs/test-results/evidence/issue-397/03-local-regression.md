# 기존 권한 시험 로컬 회귀

- 실행 ID: `fixture-397-regression-03`
- 기록 시각: 2026-10-03 13:20 KST. 정확한 명령 시작 시각은 수집하지 못함
- 목적: 기존 권한 라우팅·복제 지연 실험의 Python 판정 경로 회귀
- 위치·환경: 로컬 macOS, Python 3.14, 실제 GCP·DB 접속 없음
- 명령: `python3 -m unittest discover -s scripts/opensql -p 'test_permission_replica_lag.py' -v`
- 결과: **6/6 통과**, 실패 **0건**, 오류 **0건**, 종료 코드 **0**.
- 해석: 기존 판정 단위 테스트는 통과했다. 기존 VM 도우미의 실제 systemd·DB 동작까지 이 테스트로 검증한 것은 아니다.
