# `hwp-hwpx-unit-20261015-06`

- 시각: 2026-10-15 KST. 정확한 시작·종료 시각은 별도 수집하지 않았다.
- 위치·리비전: 격리 로컬 작업 트리의 미커밋 `feature/505`. 기준 `origin/develop`은 `31d5ac459b3da861bc957a59eb8ba433d85507c5`.
- 목적: HWP OLE 입력 스트림의 명시적 종료와 주석 수정 뒤 신규 파싱·업로드·Chunk 계약 재검증.
- 명령: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.document.service.HwpDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.HwpxDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.FileValidationServiceTest' --tests 'com.opensource.docgrid.domain.document.service.HancomParsingPipelineTest' --console plain`
- 합격 기준: 4개 클래스, 28개 테스트 전부 통과.
- 관측: Gradle `BUILD SUCCESSFUL`, JUnit XML 기준 **28개 통과, 실패 0개**. 실행 약 2초.
- 정리·한계: DB·GCS·VM 자원은 사용하지 않았다. 빌드 산출물만 로컬에 남았다. 실제 업로드→검색 E2E 결과를 뜻하지 않는다.
