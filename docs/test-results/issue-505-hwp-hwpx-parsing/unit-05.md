# `hwp-hwpx-unit-20261015-05`

- 시각: 2026-10-15 KST. 정확한 시작·종료 시각은 별도 수집하지 않았다.
- 위치·목적: 격리 로컬 작업 트리, HWPX 정상 패키지·표 텍스트와 오류 케이스 최종 단위 재검증.
- 명령: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.document.service.HwpDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.HwpxDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.FileValidationServiceTest' --tests 'com.opensource.docgrid.domain.document.service.HancomParsingPipelineTest' --console plain`
- 합격 기준: 파싱·업로드 검증·Chunk 연결 테스트 4개 클래스 모두 통과.
- 관측: JUnit XML 기준 **28개 통과, 실패 0개**(2+4+5+17개), Gradle `BUILD SUCCESSFUL`.
- 판정: **통과**. 실제 DB·저장소·임베딩 모델을 거친 제품 E2E까지 입증하지는 않는다.
