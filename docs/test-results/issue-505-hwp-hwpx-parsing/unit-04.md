# `hwp-hwpx-unit-20261015-04`

- 시각: 2026-10-15 KST. 정확한 시작·종료 시각은 별도 수집하지 않았다.
- 위치·목적: 격리 로컬 작업 트리, HWPX 패키지 XML 참조 검사와 Chunk 연결 테스트 재검증.
- 명령: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.document.service.HwpDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.HwpxDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.FileValidationServiceTest' --tests 'com.opensource.docgrid.domain.document.service.HancomParsingPipelineTest' --console plain`
- 합격 기준: 4개 클래스 모두 통과.
- 관측: **28개 실행, 26개 통과, 2개 실패**. HWPX의 `container.xml`에는 `.txt` 미리보기도 `rootfile`로 표시되는데, 검사에서 이를 패키지 XML로 잘못 판단했다.
- 판정: **실패**. `application/hwpml-package+xml`인 rootfile만 패키지 XML 확장자를 검사하도록 수정했다.
