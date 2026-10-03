# DB 조회 실패 시 정리 가드 출력 회귀 시험

- 실행 ID: `fixture-397-query-failure-11`
- 시작: 2026-10-03 14:25 KST
- 목적: DB가 꺼진 동안 `role`이 `replica`, `guard-status`가 빈 계정 수를 성공으로 보고한 실환경 반례를 차단한다.
- 위치: 로컬 작업 브랜치; Docker를 실패시키는 임시 대체 실행 파일을 사용하는 단위 시험이다. 실제 DB 장애를 추가로 주입하지 않는다.
- 명령: `python3 -m unittest discover -s scripts/opensql -p 'test_ha_fixture_cleanup.py' -v`; `bash -n scripts/opensql/permission_fixture.sh`; `git diff --check`
- 성공 기준: `role`과 `guard-status` 모두 DB 조회 실패 때 종료 코드 0이 아니고 표준 출력이 비어 있음; 기존 정리 모형 시험 전부 통과; 쉘 구문·diff 검사 통과.

| 검사 | 관측 결과 | 판정 |
| --- | --- | --- |
| `unittest discover` | **8/8 통과**, 실패 0. DB 쿼리가 종료 코드 1일 때 `role`·`guard-status` 모두 비정상 종료하고 표준 출력 0바이트 | 실패를 정상 replica/빈 계정 수로 둔갑시키지 않음 |
| `bash -n` | 종료 코드 0 | 쉘 구문 정상 |
| `git diff --check` | 종료 코드 0 | 패치 공백 오류 없음 |

최종 판정: **로컬 회귀 통과**. 실제 GCP에서 DB를 재중단시키지 않았으므로 실서버 실패 경로의 패치 후 재현 결과는 아니다.
