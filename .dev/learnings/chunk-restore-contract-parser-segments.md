# Chunk 복원 계약이 파서 Segment 설계를 제한한다

## 문제

문서 본문 보기(`DocumentQueryService.restoreContent`)는 저장된 Chunk의 `char_start`·`char_end`로 원문을 복원하며,
연속한 Segment 사이에 LF 한 칸만 허용한다. 파서가 Segment를 만들 때 줄을 버리거나 `strip()`으로 다듬으면
Segment를 LF로 이은 결과가 원문과 달라져 복원이 `DOCUMENT_CHUNKS_INCONSISTENT`로 실패한다.

## 적용 패턴

텍스트 원문을 구간으로 나누는 파서(예: `MarkdownDocumentParser`)는 줄을 그대로 보존하고, Segment를 LF로 이으면
정규화된 원문과 같아지게 만든다. 공백뿐인 앞부분이나 본문 없는 Heading은 버리지 않고 다음 Segment에 합친다.
`MarkdownDocumentParserTest.parseDocument_segmentsJoinedByLineFeedRestoreOriginalText`가 이 계약을 고정한다.

DOCX·PDF는 원문 텍스트가 따로 없어서 이 제약을 받지 않는다 (복원 대상이 Segment를 이은 텍스트 자체).
