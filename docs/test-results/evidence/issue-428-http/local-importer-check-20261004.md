# 로컬 원장 가져오기·공개 증거 검사

- 목적: GCP에서 이미 끝난 k6 실행을 원래 시각 그대로 원장에 가져오고, 비허용 필드·DB 행 누락을 거부하는지 확인한다. GCP 장애를 다시 주입하지 않는다.
- 위치: 로컬 저장소 `test/428-http` 브랜치, 실행 기반 커밋 `20c813bb2f5cff6dbcdbaf1099ad0489ea95f4d6`.
- 판정 기준: 새 단위 시험 전부 통과, 기존 원장 시험 전부 통과, 쉘 구문·압축 무결성·비식별 검사 통과.

| 실행 ID | 위치·명령 | 결과 숫자 | 판정·해석 |
| --- | --- | --- | --- |
| local-importer-preliminary | 로컬, `python3 scripts/opensql/test_import_completed_ha_k6.py -v` | 처음 작성한 3개 시험 3/3 통과 | 이후 비객체 JSON 입력 사례를 추가했다. 이 예비 실행의 정확한 시각·원시 stdout은 별도 보존되지 않았다 |
| local-importer-final | 로컬, 위 명령 재실행 | 4개 시험 4/4 통과, 약 0.032초 | 원래 실행시각 유지, 토큰 같은 비허용 필드 거부, 승인 DB 행 누락 감지, 비객체 JSON 거부 |
| local-ledger-regression | 로컬, `python3 scripts/opensql/test_ha_evidence.py -q` | 기존 11/11 통과, 약 0.269초 | 기존 원장 계약 회귀 없음 |
| local-shell-static | 로컬, `bash -n scripts/opensql/run_ha_probe_k6.sh` 및 `git diff --check` | 종료 코드 0 / 0 | runner 구문·수정 부분 공백 오류 없음 |
| local-artifact-audit | 로컬, `gzip -t`·JSON 파싱·식별자 정규식 검사 | 증거 gzip 손상 0건; 내부 IP·프로젝트 ID·계정·JWT 형태 탐지 0건 | 공개 대상 파일만 검사. 비밀 원본 stderr와 Patroni 원문 로그는 캡처하지 않았음 |

완료된 k6 원장 4개에는 `ha_evidence.py verify`를 각각 실행해 모두 `complete=true`를 받았다. 구체적인 요청·DB 대조는 각 실행의 `reconciliation.json`에 있으며, 원장 가져오기는 DB나 앱에 새 요청을 보내지 않는다. 로컬 실행은 GCP 실제 동작을 대체하지 않고 기록의 일관성을 점검한다.
