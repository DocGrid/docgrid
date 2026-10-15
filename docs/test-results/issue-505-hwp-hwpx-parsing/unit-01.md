# `hwp-hwpx-unit-20261015-01`

- 시각: 2026-10-15 21:31 KST 무렵. JUnit 실패 기록은 21:31:33 KST이며 시작 시각은 별도 수집하지 않았다.
- 위치·목적: 격리 로컬 작업 트리, HWP/HWPX 파서와 업로드 구조 검증의 첫 실행.
- 명령: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.document.service.HwpDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.HwpxDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.FileValidationServiceTest' --console plain`
- 합격 기준: 세 클래스의 정상·빈·손상·MIME 검증 전부 통과.
- 관측: **24개 실행, 23개 통과, 1개 실패**. HWPX 표 샘플의 미리보기 텍스트가 `<`로 시작해 XML로 오인됐고 SAX 구문 오류가 발생했다. PDF/DOCX를 포함한 기존 파일 검증 케이스는 이 실행에서 실패하지 않았다.
- 판정: **실패**. 비 XML 미리보기 텍스트를 XML로 분류하는 로직을 수정했다. 정확한 원본 로그 전체는 보존하지 않았고 실패 종류·건수만 실행 출력에서 기록했다.
