# bge461-unit-r1 — Python API 회귀

| 필드 | 값 |
| --- | --- |
| 시각 | 2026-10-10 04시대 KST; 초 단위 시작 시각은 원본에 보존되지 않음 |
| 목적 | 새 CPU 이미지에서 기존 단건·배치·readiness API 계약 회귀 확인 |
| 위치 | GCP 시험 VM의 격리된 일회성 Docker 컨테이너 |
| 방법 | `requirements-test.txt` 임시 설치 후 `python -m pytest -q test_main.py` |
| 성공 기준 | 기존 API 테스트 실패·오류 0건 |
| 결과 | 22 passed, 2 deprecation warnings, 5.41 s |
| 판정 | PASS. 두 warning은 Starlette·FlagEmbedding의 사용 중단 예고로 테스트 실패가 아님 |
| 정리 | `--rm` 일회성 컨테이너 종료; 기존 서비스·VM 설정 미변경 |
