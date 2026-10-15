# claim·lease 수정 후 전체 CI 통과 — run-full-ci-03

| 항목 | 기록 |
| --- | --- |
| 목적 | RAG claim·lease 수정과 시험 조건 수정을 포함한 전체 회귀 확인 |
| 시각·위치 | 2026-10-16 06:04~06:09 KST, GitHub Actions PR #515 |
| 버전·방법 | `0d06e38`, 백엔드 `./backend/gradlew -p backend build --no-daemon`, 프런트엔드 CI lint·build·test |
| 성공 기준 | 백엔드·프런트엔드 CI 성공, 백엔드 실패·오류 0개 |
| 결과 | **모두 성공.** 백엔드 XML 251개 묶음의 시험 총 1,495개, 실패·오류 0개, 건너뜀 5개. 프런트엔드 CI 성공 |
| 정리 | GitHub Actions의 임시 컨테이너 정리 단계 성공. 로컬 시험 자원과 별개 |

앞선 실패 회차 [1차](run-full-ci-01.md)와 [2차](run-full-ci-02.md)를 덮어쓰지 않았다. 이 회차의 결과는 [CI 실행](https://github.com/DocGrid/docgrid/actions/runs/38807065372)에서 확인할 수 있다. 건수는 업로드된 JUnit XML을 합산했다.
