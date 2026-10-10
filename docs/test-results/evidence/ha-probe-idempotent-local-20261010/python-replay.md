# HA probe 재전송·원장 로컬 검증

| 항목 | 기록 |
| --- | --- |
| 실행 ID | `local-ha-idem-replay-20261010-01` |
| 기록 시각 | 2026-10-10 04:50 KST |
| 위치·리비전 | 로컬 격리 작업 공간, `develop` 기준 `db4c220` + 미커밋 변경 |
| 목적 | 500·503·응답 불명만 재전송하고 201·403은 제외하는지, 201 누락과 DB 중복을 재전송 전에 보존하는지 확인 |
| 명령 | `python3 -m unittest -q test_retry_idempotent_ha_probe test_sanitize_ha_k6_events test_ha_evidence` (실행 위치: `scripts/opensql/`) |
| 성공 기준 | 대상 테스트 전부 통과, 재전송 로그에 URL·JWT 미기록 |
| 관측 | 최종 **27건 통과, 실패 0건**, 약 0.73초, 종료 코드 0 |

추가 구문 확인: Bash 스크립트 2개 `bash -n` 통과, 수정 k6 스크립트의 Node ESM 구문 확인 통과, Python 파일 3개 `py_compile` 통과. 앞선 반복 실행과 샌드박스 차단 시도는 각각 별도 기록으로 남겼다.

이 검증의 HTTP 송신자는 모의 함수다. GCP VM 장애, 실제 LB 응답, k6 Remote Write 및 운영 앱 배포는 실행하지 않았다.
