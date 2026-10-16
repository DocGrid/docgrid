# DocGrid 예제 문서

README 「빠른 실행」 뒤 검색과 RAG 답변을 바로 확인하기 위한 예제 문서 15건입니다. 가상 회사
"데모랩 주식회사"의 짧은 사내 규정이며, 실제 회사·인물·규정과 관계없습니다.

```bash
python3 scripts/demo/seed_demo_corpus.py
```

스크립트는 `manifest.json`의 제목·설명으로 문서를 PUBLIC 업로드하고, 인덱싱 완료와 예시 검색까지
확인합니다. 사용법은 [README 「빠른 실행」 6단계](../../README.md#6-핵심-흐름-확인)를 참고하세요.

## 문서 목록

| 파일 | 형식 | 제목 |
|---|---|---|
| `01-remote-work-policy.pdf` | PDF | 재택근무 운영 규정 |
| `02-business-travel-expense.pdf` | PDF | 출장 및 여비 규정 |
| `03-annual-leave-guide.pdf` | PDF | 연차휴가 사용 지침 |
| `04-information-security.pdf` | PDF | 정보보안 기본 규정 |
| `05-it-asset-management.pdf` | PDF | IT 자산 관리 규정 |
| `06-family-event-support.docx` | DOCX | 경조사 지원 규정 |
| `07-training-support.docx` | DOCX | 교육훈련비 지원 규정 |
| `08-corporate-card.docx` | DOCX | 법인카드 사용 지침 |
| `09-onboarding-guide.docx` | DOCX | 신규 입사자 온보딩 안내 |
| `10-meeting-room-booking.docx` | DOCX | 회의실 및 공용 장비 예약 규칙 |
| `11-records-retention.hwpx` | HWPX | 문서 보존 및 폐기 기준 |
| `12-incident-response.hwpx` | HWPX | 장애 대응 및 비상연락 절차 |
| `13-flexible-work.hwpx` | HWPX | 유연근무제 운영 지침 |
| `14-personal-data-handling.hwpx` | HWPX | 개인정보 처리 지침 |
| `15-club-support.hwpx` | HWPX | 사내 동호회 지원 규정 |

문서마다 본문이 300~850자 정도로 짧습니다. 2026-10-16 로컬 실행 기준으로 DOCX는 장 제목(`Heading1`)마다
Chunk가 나뉘어 문서당 6~7개, PDF와 HWPX는 문서당 1개로 모두 42개 Chunk가 인덱싱되었습니다.

## 만든 방법

- 본문은 이 저장소 기여자가 2026-10-16에 시연용으로 직접 작성했습니다. 외부 규정이나 문서를 복사하지
  않았습니다. 법령을 언급하는 문장은 "법령을 따른다"는 수준으로만 적었으며 법률 자문이 아닙니다.
- 같은 본문을 Backend가 파싱에 쓰는 라이브러리로 각 형식에 저장했습니다.
  - PDF: Apache PDFBox 3.0.8. A4, 나눔고딕(NanumGothic) 글꼴 중 문서에 쓰인 글자만 포함합니다.
  - DOCX: Apache POI 5.5.1. 제목은 `Title`, 장 제목은 `Heading1` 스타일이라 검색 결과에 Section 경로가 나옵니다.
  - HWPX: hwpxlib 1.0.9의 빈 문서(`BlankFileMaker`)에 문단을 추가했습니다.
- 생성 뒤 같은 라이브러리로 본문 텍스트가 모두 추출되는지 확인했습니다.
- 문서를 바꿀 때는 Word나 한컴오피스 등에서 같은 형식으로 저장하고 `manifest.json`의 파일명·제목·설명을
  맞추면 됩니다. 파일 바이트가 바뀌면 업로드 키도 바뀌므로, 이미 올린 계정에는 새 문서로 추가됩니다.

## 라이선스

문서 본문과 `manifest.json`은 저장소의 [Apache License 2.0](../../LICENSE)을 따릅니다.

PDF에 포함된 나눔고딕 글자 일부는 NHN(현 NAVER)의 글꼴이며, 나눔글꼴은 SIL Open Font License 1.1로
배포되어 문서에 글꼴을 포함하는 것을 허용합니다. 포함한 글꼴의 OS/2 `fsType`은 0(포함 제한 없음)입니다.
글꼴 파일 자체는 저장소에 넣지 않았습니다.
