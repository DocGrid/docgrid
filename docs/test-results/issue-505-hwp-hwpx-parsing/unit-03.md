# `hwp-hwpx-unit-20261015-03`

- 시각: 2026-10-15 KST. 정확한 시작·종료 시각은 별도 수집하지 않았다.
- 위치·목적: 격리 로컬 작업 트리, HWP 헤더 암호 비트와 HWPX manifest 암호화 표시가 정상 인덱싱되지 않는지 확인.
- 명령: `./backend/gradlew -p backend test --tests 'com.opensource.docgrid.domain.document.service.HwpDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.HwpxDocumentParserTest' --tests 'com.opensource.docgrid.domain.document.service.FileValidationServiceTest' --console plain`
- 합격 기준: 기존 테스트와 새 암호화 표시 테스트 모두 통과.
- 관측: **26개 통과, 실패 0개**, Gradle `BUILD SUCCESSFUL`.
- 판정: **통과**. 실제 비밀번호로 암호화한 문서 원본을 푼 시험은 아니며, 기존 샘플의 암호화 표시를 시험 중 메모리에서 변형했다.
