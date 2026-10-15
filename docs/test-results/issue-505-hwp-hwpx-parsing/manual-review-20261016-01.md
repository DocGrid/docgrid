# HWP·HWPX 변경 수동 검토

- 검토 ID: `hwp-hwpx-review-20261016-01`
- 검토 시각: 2026-10-16 00:35~00:41 KST
- 대상: PR #506, 리비전 `35c6f0dde3f67084f58350d49dc1b4c035908d80`
- 방법: 파서·업로드 검증·샘플 출처·패키징 기록을 읽고 Maven Central POM의 좌표와 라이선스 표시를 대조
- 통과 기준: 요청된 네 검토 항목에서 병합을 막는 문제를 발견하지 않음

| 항목 | 관측 근거 | 판정 |
| --- | --- | --- |
| HWPX ZIP 크기·XML | `HwpxDocumentParser`는 ZIP 항목 수 512개, 단일 해제 항목 32 MiB, 전체 해제량 100 MiB를 제한한다. XML을 보안 SAX 설정으로 재파싱하고 DTD·외부 개체를 거부하며 임시 파일을 `finally`에서 삭제한다. | 요청 범위 통과 |
| 업로드 MIME·구조 | HWP/HWPX의 명시적 MIME과 브라우저 범용 MIME을 허용하며 HWP OLE·FileHeader, HWPX ZIP·mimetype을 확인한다. 브라우저에서 두 샘플 모두 업로드됐다. | 요청 범위 통과 |
| 샘플 출처 | `backend/src/test/resources/hancom/README.md`에 상류 커밋·원본 링크·SHA-256이 있고, 포함된 4개 파일의 로컬 해시가 모두 기재값과 일치한다. | 통과 |
| 라이브러리·고지 | Maven Central POM에서 `kr.dogfoot:hwplib:1.1.11`, `kr.dogfoot:hwpxlib:1.0.9`가 모두 Apache-2.0으로 표시된다. README와 실행 JAR 라이선스 고지는 선행 패키징 기록에서 확인됐다. | 통과 |

수동 검토는 모든 가능한 악성 HWPX 입력에 대한 보안 보증이나 라이선스 법률 의견이 아니다. 실행 JAR 고지는 선행 `package-02.md` 기록을 재검토한 것이며 이번 검토에서 새 패키징 시험을 수행하지 않았다. HWP `ABC` 질문의 생성 답변 품질은 별도 검색 품질 문제로 기록한다.

- [hwplib Maven Central](https://central.sonatype.com/artifact/kr.dogfoot/hwplib/1.1.11)
- [hwpxlib Maven Central](https://central.sonatype.com/artifact/kr.dogfoot/hwpxlib/1.0.9)
