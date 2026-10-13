# Facade·GCS 단위 시험 1회차

- 실행 ID: `upload-candidate-unit-20261013-01`
- 시각: 2026-10-13 KST. 시작 시각은 별도 보존되지 않았고 결과 기록 시각은 2026-10-13 22:50 KST.
- 목적: 키 없는 업로드 보상 동작과 GCS generation 고정 읽기·조건부 삭제 경계 확인.
- 위치: 로컬 JVM, 모의 GCS SDK. 실제 GCS 접근 없음.
- 명령: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.document.service.DocumentUploadFacadeTest' --tests 'com.opensource.docgrid.domain.document.storage.GcsStorageServiceTest' --offline --no-daemon`
- 성공 기준: 두 클래스의 시험 13건 통과, 실패·오류·건너뜀 0건.
- 관측: Gradle `BUILD SUCCESSFUL in 5s`, 종료 코드 0. 이 회차의 JUnit XML은 후속 실행에서 교체되어 개별 XML은 보존되지 않았음.
- 판정: 통과. 최종 코드 기준 같은 범위의 재실행은 [unit-02.md](unit-02.md) 참조.
