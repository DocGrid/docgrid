# 쉘 구문·패치 정적 검사

- 실행 ID: `fixture-397-syntax-04`
- 기록 시각: 2026-10-03 13:20 KST. 정확한 명령 시작 시각은 수집하지 못함
- 목적: VM 도우미 구문 오류와 패치 공백 오류 조기 발견
- 위치: 로컬 작업 브랜치
- 명령·결과: `bash -n scripts/opensql/permission_fixture.sh` 종료 코드 **0**; `git diff --check` 종료 코드 **0**.
- 해석: 파싱·패치 형식 검사만 통과. 실제 systemd 타이머 재시도 검증을 대체하지 않는다.
