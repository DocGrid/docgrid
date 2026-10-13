# 단위 시험 실행 01 · 통과

- 실행 ID: `version-upload-unit-20261014-01`
- 시각: 2026-10-14 KST. 분·초 단위 시작 시각은 수집하지 않음.
- 목적: 컨트롤러의 선택적 요청 키 전달 및 Facade·트랜잭션 서비스 호출 회귀 확인.
- 위치·리비전: 로컬 작업 브랜치, 커밋 전 변경. 클라우드 접근 없음.
- 방법: `./backend/gradlew -p backend test --tests '*DocumentUploadControllerTest' --tests '*DocumentVersionUploadFacadeTest' --tests '*DocumentVersionUploadServiceTest'`
- 성공 기준: 세 시험 클래스의 Gradle 종료 코드 0, 실패 0건.
- 관측: 종료 코드 0. 이 최초 실행의 JUnit XML 집계는 후속 실행으로 교체되어 건수는 보존하지 못함.
- 판정: 세 클래스가 통과했으나 정확한 시험 건수는 이 실행의 증거로 주장하지 않는다. 최종 건수는 [단위 시험 실행 02](unit-02.md)에 있다.
