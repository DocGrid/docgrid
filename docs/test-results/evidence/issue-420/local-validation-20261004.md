# 계측·분석 도구 로컬 검증 · `ha420local001`

| 항목 | 내용 |
| --- | --- |
| 시각 | 2026-10-04 KST |
| 목적 | 장애 원장·민감정보 sanitizer·1초 계측·새 복구시간 분석 코드의 회귀 확인 |
| 실행 위치 | 격리된 `test/420` 저장소 작업 트리, 로컬 Python·Bash |
| 완료 기준 | 단위 테스트 모두 통과, shell 문법과 diff 공백 오류 0 |

| 명령 | 결과 요약 | 해석 |
| --- | --- | --- |
| `python3 -m unittest discover -s scripts/opensql -p 'test_*.py' -q` | **90/90 통과**, 0.752초 | 기존 원장·sanitizer·계측과 새 분석기 회귀 통과. 테스트 출력의 임의 UUID는 합성 값이다. |
| `bash -n scripts/opensql/ha_primary_fault_app.sh scripts/opensql/ha_primary_process_fault.sh scripts/opensql/run_ha_probe_k6.sh` | 종료 코드 0 | Bash 파싱 오류 없음. 실제 GCP 동작은 별도 실행 기록을 봐야 한다. |
| `git diff --check` | 종료 코드 0 | 추적 파일 공백 오류 없음. |
| 실측 원장 `ha_evidence.py verify` | `ha420base001`, `ha420proc001`, `ha420vm00001`, `ha420vm00002` 각 완료·검증 | 마지막 실행은 **무효**로 표기하며 통과 표본에 합치지 않는다. |

첫 로컬 분석기 단위 시험에서 복구 시작 버킷의 예상값을 잘못 적어 1건 실패했다. 버킷 2–6초가 연속 성공이면 안정 복구의 시작은 **2초**이지 6초가 아니라는 정의에 맞춰 기대값을 수정한 뒤 2/2가 통과했고, 전체 90/90도 통과했다. 실패 시도를 없던 일로 기록하지 않는다.
