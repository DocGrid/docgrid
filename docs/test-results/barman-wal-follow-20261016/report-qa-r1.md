# DR-BARMAN-REPORT-QA-20261016-R1 — 기록·단위 시험 재검증

- 시각: 2026-10-16 약 22:22 KST.
- 목적·위치: 로컬 분리 작업 트리에서 Barman 감시기 단위 시험과 실행 기록 품질 검증.
- 방법: `python3 -m unittest discover -s scripts/opensql -p test_barman_wal_follow_primary.py -v`, `python3 /tmp/docgrid_barman_report_qa.py`, `git diff --check`. 민감 패턴은 검사 코드에서 개수만 출력하고 원문을 출력하지 않았다. 임시 검사 스크립트는 저장소에 포함하지 않는다.
- 통과 기준: 단위 시험 전부 성공, 상대 링크 오류·민감 패턴·공백 오류 0건.
- 관측: 단위 시험 **11/11 성공**, 문서 68개에서 상대 링크 62개·깨진 링크 0개, 비공개 IPv4·자격값 할당·개인 키 블록·프로젝트 ID 형식의 민감 패턴 각 0개, `git diff --check` 종료 코드 0.
- 판정: **통과**. 패턴 검사는 알려진 형식만 확인하므로 모든 비공개 값의 부재를 증명하지 않는다. 스테이징 후 `git diff --cached --check`를 별도 확인한다.
