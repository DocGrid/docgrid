# #505 HWP 5.0·HWPX 본문 및 표 텍스트 인덱싱

## 목표와 경계

기존 PDF·DOCX·TXT·MD 업로드→원본 저장→비동기 파싱→Chunk→임베딩→검색 경로에 HWP 5.0과 HWPX를 추가한다. 본문과 표 셀의 텍스트만 검색 대상으로 삼는다. 이미지 OCR, 원본 미리보기 변환, HWP 3.x, HWP↔HWPX 형식 변경을 동반한 새 버전 업로드는 제외한다. HWP/HWPX에는 신뢰할 수 있는 페이지 번호가 없으므로 `pageNo=null`인 Segment를 만든다.

## 기존 구조와 변경 지점

`FileValidationService`는 메타데이터를 확인하고 `DocumentType`을 확정한다. `DocumentChunkTransactionService.prepare()`는 DB에서 파일 위치와 형식을 스냅샷으로 읽고, `DocumentParsingService`는 트랜잭션 밖에서 원본을 읽어 `DocumentParserRegistry`에 전달한다. 새 `HwpDocumentParser`와 `HwpxDocumentParser`는 `DocumentContentParser` 계약을 구현해 본문·표 텍스트를 `ParsedDocumentSegment`로 반환한다. Chunk 저장, 임베딩 및 검색 경로는 변경하지 않는다.

## 업로드 정책

| 확장자 | 내부 형식 | 허용 요청 MIME | 업로드 구조 검사 |
| --- | --- | --- | --- |
| `.hwp` | `HWP` | `application/x-hwp`, `application/haansofthwp`, `application/vnd.hancom.hwp`, `application/octet-stream` | OLE 매직 바이트와 `FileHeader` 스트림의 HWP 5.x 서명·버전 |
| `.hwpx` | `HWPX` | `application/hwp+zip`, `application/vnd.hancom.hwpx`, `application/zip`, `application/octet-stream` | ZIP 매직 바이트와 `mimetype=application/hwp+zip` |

범용 MIME은 해당 구조 검사에 성공할 때만 허용한다. 브라우저·OS가 실제 한글 문서에 범용 MIME을 붙이는 경우를 수용하기 위해서다. 기존 네 형식의 MIME·업로드 검증은 바꾸지 않는다. Worker의 준비 단계도 같은 MIME 목록을 사용한다. 암호 문서, 손상 문서, 검색 가능한 텍스트가 없는 문서는 정상 `INDEXED`가 되어서는 안 된다. 새 버전 형식은 최초 문서와 일치해야 한다.

## 파싱과 자원 제한

- HWP: `hwplib`로 HWP 5.x 본문을 읽고 `InsertControlTextBetweenParagraphText` 방식으로 표 셀 텍스트를 본문 순서에 삽입한다. 암호 여부는 파일 헤더에서 확인하며, 지원되지 않는 문서는 파싱 실패로 종료한다.
- HWPX: `hwpxlib`는 `File`만 읽으므로 50 MB 업로드 상한이 적용된 바이트를 임시 파일에 쓰고 `finally`에서 삭제한다. ZIP 엔트리 수와 해제 총량을 제한한다. 라이브러리의 XML Reader는 기본 SAX 설정을 사용하므로, `.xml`·`.hpf` 항목을 외부 개체·DTD가 비활성화된 파서로 먼저 검사한다. 패키지·콘텐츠 manifest가 다른 확장자의 XML을 참조하면 거부한다. 이후 라이브러리의 텍스트 추출기에서 표 셀 텍스트를 포함한다.
- 추출 문자열의 줄바꿈을 LF로 정규화한다. 빈 문자열은 `DOCUMENT_CONTENT_EMPTY`, 손상·지원하지 않는 구조는 `DOCUMENT_PARSING_FAILED`로 처리한다. 암호 오류를 안정적으로 구분할 수 있으면 별도 코드로 구분하고, 그렇지 않으면 파싱 실패로 기록한다. 오류 로그에 원문이나 로컬 저장 경로를 남기지 않는다.
- 표 텍스트는 셀을 읽은 순서로 검색한다. 병합 셀의 화면 배치 재현은 약속하지 않는다.

## 의존성·라이선스·fixture

Maven Central의 `kr.dogfoot:hwplib:1.1.11`과 `kr.dogfoot:hwpxlib:1.0.9`를 사용한다. 두 버전의 POM 및 업스트림 `license.txt`는 Apache-2.0을 표기한다. POM상 새 런타임 전이 의존성은 없다. 이 저장소의 Apache-2.0과 충돌하지 않는 것으로 판단하지만 대회 최종 검증 결과를 대신하지는 않는다. README의 외부 구성요소 표에 정확한 좌표·용도·라이선스와 업스트림 링크를 적고 배포물에 고지가 유지되는지 확인한다.

확인한 업스트림 커밋의 최상위에는 별도 `NOTICE` 파일이 없었다. 출처를 남긴 샘플 문서 4개와 두 라이브러리의 라이선스는 저장소 루트의 Apache-2.0 `LICENSE`, 샘플 출처 표 및 README 외부 구성요소 표로 고지한다. 향후 실행 JAR·컨테이너를 별도로 배포할 때는 그 배포물에서도 라이선스 사본과 해당 버전의 고지가 전달되는지 다시 확인한다.
실행 JAR에는 루트 `LICENSE`를 `META-INF/LICENSE`로 복사하고, `META-INF/THIRD-PARTY-HANCOM.txt`에 두 라이브러리의 좌표·출처를 포함한다. 이는 새 라이브러리의 고지 경계이며 기존 모든 전이 의존성의 종합 라이선스 감사를 대신하지 않는다.

정상·빈 fixture는 직접 생성하거나 라이선스가 확인된 업스트림 샘플만 사용한다. 업스트림 바이너리 샘플을 가져올 경우 원본 URL, 커밋, SHA-256과 라이선스를 기록한다. 손상·암호 fixture는 직접 만든 변형 또는 재배포 권한이 확인된 파일만 사용한다. 재배포 권한이 불명확한 제3자 문서는 저장소에 추가하지 않는다.

## 검증

1. 형식별 정상 본문·표 셀 텍스트와 Chunk 내용을 검증한다.
2. 손상·암호·빈 HWP/HWPX가 정상 인덱싱으로 진행하지 않는지 확인한다.
3. HWP/HWPX 각각 실제 업로드→`INDEXED`→검색을 로컬에서 확인한다. 실모델이 없다면 그 구간은 미검증으로 명시한다.
4. `./backend/gradlew -p backend test` 전체와 프런트엔드 검증을 실행해 PDF·DOCX 회귀를 확인한다.

## 근거

- [hwplib Maven Central POM](https://central.sonatype.com/artifact/kr.dogfoot/hwplib/1.1.11)
- [hwpxlib Maven Central POM](https://central.sonatype.com/artifact/kr.dogfoot/hwpxlib/1.0.9)
- [hwplib Apache-2.0 원문](https://github.com/neolord0/hwplib/blob/main/license.txt)
- [hwpxlib Apache-2.0 원문](https://github.com/neolord0/hwpxlib/blob/main/license.txt)
- [2026 대회 라이선스 검증 안내](https://www.oss.kr/pages/2)
