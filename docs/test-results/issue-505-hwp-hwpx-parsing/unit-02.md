# `hwp-hwpx-unit-20261015-02`

- 시각: 2026-10-15 KST. 정확한 시작·종료 시각은 별도 수집하지 않았다.
- 위치·목적: 격리 로컬 작업 트리, XML 오분류 수정 뒤 동일 샘플 재검증.
- 명령: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.document.service.HwpDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.HwpxDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.FileValidationServiceTest' --console plain`
- 합격 기준: 앞선 실패가 사라지고 세 클래스가 모두 통과.
- 관측: **24개 통과, 실패 0개**, Gradle `BUILD SUCCESSFUL`.
- 판정: **통과**. 이 실행은 암호화 표시와 Chunk 연결 테스트를 추가하기 전의 범위다.
