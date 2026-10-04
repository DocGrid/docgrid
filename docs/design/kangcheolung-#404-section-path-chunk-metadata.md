# #404 문서 구조 기반 Chunk 섹션 경로 저장과 임베딩·출처 반영

- 관련 이슈: closes #404
- 작성일: 2026-10-04
- 상태: 구현·테스트·벤치마크 완료 (커밋 전). 작업 중 발견한 Ollama 요청 XML 전송 문제도 이 브랜치에서 함께 수정했다 (§10).

## 1. 배경

멘토링에서 "사이즈 기반 청킹 대신 문서 구조 기준 청킹을 고려할 수 있다"는 개선안이 나왔다.
현재 파이프라인은 문서 구조를 절반만 쓴다.

| 형식 | 구간 경계 | 섹션 제목 (변경 전) |
|---|---|---|
| DOCX | Heading·Title 스타일마다 새 구간 (레벨 구분 없음) | 마지막 Heading 한 줄만 저장 |
| PDF | 페이지 1장 = 구간 1개 | 항상 null |
| TXT | 파일 전체 = 구간 1개 | 없음 |
| MD | 파일 전체 = 구간 1개 | 없음 (`#` 미인식) |

구간 안에서는 `FixedSizeChunker`가 1000자 단위로 자르므로 섹션이 길면 둘째 청크부터는 제목이 본문에 없다.
`section_title`은 저장만 되고 **임베딩 입력과 검색·출처 응답 어디에도 쓰이지 않았다.** `metadata_json` 컬럼도
있었지만 파서가 항상 null을 채웠다. 그 결과 다음 문제가 있었다.

1. 같은 서식의 문서가 여러 개일 때(약관 세트 등) 본문만으로는 어느 문서·조항의 청크인지 벡터에 담기지 않는다.
2. Heading 레벨을 버려 "상위 > 하위" 계층을 알 수 없다.
3. 검색 결과·출처에 섹션이 없어 DOCX처럼 페이지가 없는 문서는 출처 위치를 알 수 없다.
4. MD는 `#` 제목이 있어도 구조를 쓰지 못한다.

## 2. 목표와 범위

섹션 경로를 만들어 저장하고, **문서 청크의 임베딩 입력**과 **검색·출처 응답**에 반영한다.
질문 임베딩과 검색 방식(유사도 계산, 필터)은 바꾸지 않는다.

범위 밖: 문장·문단 경계 분할, PDF 제목 인식, 기존 문서 재인덱싱, 하이브리드 검색(JSONB/GIN 전환 포함).

## 3. 설계 결정

### 3.1 경로는 `metadata_json`에 저장한다 (스키마 변경 없음)

`document_chunks.metadata_json`(TEXT)에 아래 JSON을 저장한다. `section_title`은 지금처럼 마지막 제목만 둔다.

```json
{"headingPath":["3. 환불 정책","3.2 개봉 후 환불"],"headingLevel":2}
```

- 경로 문구는 문서에 적힌 제목 글자 그대로다. 번호("3.")를 해석하거나 만들지 않는다.
- Flyway 마이그레이션이 필요 없다. 컬럼과 파서→청커→저장 경로는 이미 연결돼 있고 값만 비어 있었다.
- 유효한 JSON으로 저장하므로 나중에 하이브리드 검색에서 JSONB로 바꿀 때 값 형식을 그대로 옮길 수 있다.
- 읽기·쓰기는 `SectionPath` 한 곳에서 한다. 경로가 없거나 JSON이 손상돼 있으면 오류 없이 "경로 없음"으로 취급한다.

### 3.2 경로 규칙

| 형식 | Heading 판정 | 레벨 |
|---|---|---|
| DOCX | 스타일 ID가 `heading`으로 시작하거나 `title` | `Heading1`→1, `Heading2`→2, 숫자 없는 Heading→1, `Title`→0 |
| MD | ATX Heading (`#`~`######`), 코드 블록 안은 제외 | `#` 개수 |

- 새 Heading이 오면 같거나 깊은 레벨의 이전 Heading을 걷어내고 추가한다 (`HeadingTrail`).
- 레벨을 건너뛰면(H1 → H3) 상위 Heading 아래에 그대로 둔다. 경로는 `[H1, H3]`가 된다.
- 문서 `Title`은 레벨 0이라 모든 Heading의 상위 경로가 된다. 서비스명이 제목에만 있는 문서 세트에서 이 경로가 구분 정보가 된다.
- 첫 Heading 이전 본문은 경로가 없다.

### 3.3 Markdown 파서를 분리한다

`TextDocumentParser`는 TXT와 MD를 함께 처리했고 `parseDocument(byte[])`에 형식 정보가 없다. 그래서 MD를 `MarkdownDocumentParser`로
분리하고 `TextDocumentParser`는 TXT만 맡는다. 디코딩·BOM·줄바꿈 정규화는 `TextDocumentParser.parse()`를 재사용한다.

문서 본문 보기(`DocumentQueryService.restoreContent`)는 청크의 글자 범위로 원문을 복원하며 구간 사이에 LF 한 칸만 허용한다.
따라서 MD 파서는 **줄을 버리거나 다듬지 않고**, Segment를 LF로 이으면 정규화된 원문과 같아지게 한다 (테스트로 고정).

- **본문이 없는 Heading은 다음 Heading의 Segment에 합친다.** Heading 한 줄짜리 청크가 검색 결과를 차지하지 않게 하기 위해서다.
  합쳐도 하위 Segment의 경로가 상위 Heading을 포함하므로 정보는 사라지지 않는다. 문서 끝의 본문 없는 Heading은 원문 보존을 위해 별도 Segment로 둔다.
- 코드 블록(` ``` `, `~~~`) 안의 `#`은 제목으로 보지 않는다. 제목 글자가 없거나 닫는 `#`만 남은 줄(`# #`, `## ##`)도 CommonMark의 빈 제목이라 제목으로 보지 않고 본문 줄로 둔다 (CodeRabbit 리뷰에서 확인해 반영).
- 지원하지 않는 것: Setext Heading(`===`, `---`), HTML Heading, 들여쓰기 코드 블록.

### 3.4 임베딩 입력만 바꾸고 저장 본문은 그대로 둔다

`ChunkSnapshot`에 `embeddingText`를 추가했다. 임베딩 서버에는 `embeddingText`를 보내고, 완료 단계의 "스냅샷 본문 == DB `chunk_text`"
일치 검증([`DocumentEmbeddingTransactionService.validatePreparedChunks`])은 원문 `chunkText`로 계속 한다.

- `embeddingText` = 경로가 있으면 `경로(" > "로 연결) + "\n" + 본문`, 없으면 본문 그대로.
- `chunk_text`, `content_hash`, 글자 범위, 토큰 수는 바뀌지 않는다. 문서 본문 보기와 무결성 검증이 영향받지 않는다.
- 경로가 너무 길면(200 code point 초과) 상위 Heading부터 버리고, 마지막 Heading 하나만으로도 길면 그 Heading을 잘라낸다.
- `section_title`만 있고 `metadata_json`이 없는 기존 청크는 임베딩 입력을 바꾸지 않는다. 기존 문서의 재임베딩(복구 등) 결과가 이전과 같다.

### 3.5 응답에 `sectionPath`를 추가한다

| DTO | 필드 | 값 |
|---|---|---|
| `SearchResultItem` | `sectionPath` (String, nullable) | `metadata_json`의 경로를 `" > "`로 연결. 없으면 `section_title`. 둘 다 없으면 null |
| `CitationResponse` | `sectionPath` (String, nullable) | 위와 같음 |

필드 추가이므로 기존 필드는 그대로이고 하위 호환이다. 응답을 만드는 네 경로가 모두 같은 규칙을 쓴다.

| 경로 | 값의 출처 |
|---|---|
| `POST /search` 신규 검색 | 벡터 검색 쿼리가 `section_title`·`metadata_json`을 함께 조회 |
| `GET /search/{queryId}` 재조회, RAG 출처 재구성 | 저장된 `SearchResult`·`ResponseCitation`의 청크 엔티티 |
| 대화 조회 | 조회 전용 Projection에 `sectionTitle`·`metadataJson` 추가 (SQL 횟수 5회 유지, 통합 테스트로 확인) |
| MCP 검색 도구 | 응답 항목 복사 시 `sectionPath` 유지 |

`response_citations`에는 컬럼을 추가하지 않았다. 출처 재조회는 청크를 거치므로 필요 없다.

## 4. API 명세와 오류 케이스

변경되는 API는 위 응답 필드 추가뿐이다. 요청 형식과 상태 코드는 바뀌지 않는다.

```json
{
  "rank": 1,
  "documentId": 7,
  "chunkId": 120,
  "documentTitle": "쇼핑몰 이용약관",
  "chunkText": "...",
  "pageNo": null,
  "sectionPath": "쇼핑몰 이용약관 > 3. 환불 정책 > 3.2 개봉 후 환불",
  "similarityScore": 0.812345
}
```

| 상황 | 동작 |
|---|---|
| PDF·TXT, 제목이 없는 문서 | `sectionPath: null`, 임베딩 입력은 본문만 (이전과 동일) |
| 경로 도입 전 DOCX 청크 | `sectionPath`는 저장된 `section_title`, 임베딩 입력은 이전과 동일 |
| `metadata_json`이 비었거나 손상됨 | 오류 없이 "경로 없음"으로 처리 |
| DOCX·MD 파싱 실패 | 기존 오류 코드 그대로 (`DOCUMENT_PARSING_FAILED`, `DOCUMENT_CONTENT_EMPTY`, `DOCUMENT_TEXT_DECODING_FAILED`) |

새 오류 코드는 없다.

## 5. 변경 범위

| 영역 | 파일 | 내용 |
|---|---|---|
| 파서 | `SectionPath`, `HeadingTrail` (신규) | 경로 JSON 읽기·쓰기, 계층 유지 |
| 파서 | `DocxDocumentParser` | Heading 레벨 해석, 경로 Metadata |
| 파서 | `MarkdownDocumentParser` (신규), `TextDocumentParser` | MD 분리, TXT 전용으로 축소 |
| 임베딩 | `DocumentEmbeddingTransactionService`, `DocumentEmbeddingGenerator`, `AdaptiveEmbeddingBatchPlanner` | `embeddingText` 도입, 입력·배치 길이 계산에 사용 |
| 검색 | `VectorSearchRepository`, `VectorSearchRow`, `VectorSearchCandidate`, `SearchResultItem`, `CitationResponse` | 섹션 조회와 `sectionPath` |
| 대화·출처 | `SearchResultRepository`, `ResponseCitationRepository`, `Conversation*Projection`, `SearchConversationQueryService` | 같은 규칙 적용 |
| MCP | `DocGridMcpTools` | 응답 항목 복사 시 필드 유지 |
| 외부 호출 | `OllamaClient` | 요청에 JSON Content-Type 명시 (§10, 이슈 본래 범위 밖의 별도 수정) |
| 업로드 검증 | `FileValidationService`, `DocumentChunkTransactionService` | `.md`에 한해 허용 MIME 확대 (§11) |
| 빌드 | `build.gradle` | `sectionPathQualityTest` 태스크, 일반 테스트에서 제외 |

## 6. 호환성과 운영 영향

- **신규 업로드·새 버전부터 적용된다.** 기존 문서는 이전 방식의 벡터를 그대로 가진다. 재인덱싱은 후속 청킹 이슈까지 끝난 뒤 한 번에 한다.
  그 전까지 같은 환경에 두 방식의 벡터가 섞이며, 점수 분포 차이는 아직 측정하지 않았다.
- **임베딩 배치가 작아질 수 있다.** 배치의 글자 수 상한 기본값이 4,000이고 청크는 최대 1000자라서, 경로가 붙은 청크 4개는 상한을
  넘어 3개씩 나뉠 수 있다. 호출 횟수가 늘 수 있으나 실측하지 않았다. 문제가 되면 `EMBEDDING_DOCUMENT_MAX_CODE_POINTS`(기본 4000)를 조정한다.
- **중복**: DOCX 구간의 첫 청크는 본문에 Heading이 이미 있어서 마지막 Heading이 임베딩 입력에 두 번 들어간다.
- **롤백**: 코드를 되돌리면 `metadata_json`은 사용되지 않는 값으로 남을 뿐 해롭지 않다. 이미 경로로 임베딩된 벡터는 재인덱싱 전까지 유지된다.

## 7. 검색 품질 벤치마크

### 7.1 방법

운영 파서·청커를 그대로 쓰고, 같은 청크 집합을 두 방식으로 임베딩해 비교한다. 이전 커밋으로 되돌리지 않고 기준선을 재현할 수 있다.

- **본문만**: 변경 전 방식
- **경로 + 본문**: 변경 후 방식 (`SectionPath.embeddingInput`)
- 모델은 실제 `BAAI/bge-m3`(로컬 Embedding Server), 순위는 메모리 내 Exact Cosine, DB·ANN은 쓰지 않는다. 정답은 해당 문서에서 근거 문구를 포함한 청크다.
- 코퍼스: 서비스 4종(쇼핑몰·구독서비스·숙박예약·중고거래) × 조항 6개의 약관 문서 4건을 DOCX와 MD로 각각 생성한다. 본문은 모든 문서가 같은 문장 틀에 숫자만 다르고,
  서비스 이름은 문서 제목에만 나온다. 질문은 "{서비스} 이용약관의 {조항}에서 {항목}은 얼마인가?" 형태의 24개다.
- 대조: 문서 1건만 남겨 서비스 구분이 필요 없는 조건(6개 질문)에서 경로가 해가 되는지 본다.

### 7.2 결과

| 형식 | 시나리오 | 질문 | 청크 | 방식 | Hit@1 | Hit@3 | MRR@10 |
|---|---|---:|---:|---|---:|---:|---:|
| DOCX | 4개 문서 | 24 | 48 | 본문만 | 0.2083 | 0.7917 | 0.4965 |
| DOCX | 4개 문서 | 24 | 48 | 경로 + 본문 | 1.0000 | 1.0000 | 1.0000 |
| MD | 4개 문서 | 24 | 48 | 본문만 | 0.2500 | 0.7500 | 0.5139 |
| MD | 4개 문서 | 24 | 48 | 경로 + 본문 | 1.0000 | 1.0000 | 1.0000 |
| DOCX | 1개 문서 (대조) | 6 | 12 | 본문만 / 경로 + 본문 | 1.0000 / 1.0000 | 1.0000 / 1.0000 | 1.0000 / 1.0000 |
| MD | 1개 문서 (대조) | 6 | 12 | 본문만 / 경로 + 본문 | 1.0000 / 1.0000 | 1.0000 / 1.0000 | 1.0000 / 1.0000 |

원본 수치: [`docs/test-results/evidence/issue-404/section-path-quality.json`](../test-results/evidence/issue-404/section-path-quality.json)

### 7.3 해석과 한계

- **이 결과는 효과가 가장 잘 드러나는 조건의 값이며 실제 문서에서 같은 폭으로 개선된다는 뜻이 아니다.** 4개 문서의 본문이 숫자만 다르게 설계돼서 본문만으로는
  서비스를 구분할 수 없고, 본문만 방식의 Hit@1이 우연 수준(4개 중 1개 = 0.25)에 가깝다. 경로 방식이 1.0이 된 것은 서비스 이름이 경로에만 있고 질문에 있기 때문이다.
- 실제 문서에서는 본문에 문맥 단서가 더 많아 개선 폭이 작을 것이다. 정량 기대치는 실제 문서 코퍼스로 별도 측정해야 한다.
- 대조 시나리오는 두 방식 모두 1.0으로 **포화돼서 해가 없음을 보여주지만 해가 있어도 드러나지 않는 약한 대조**다. 경로가 청크 간 변별력을 떨어뜨리는지는 이 측정으로 단정할 수 없다.
- 측정은 질문 24개·문서 4건의 작은 합성 코퍼스이고 1회 실행이다. 모델 호출은 결정적이라고 가정했으나 반복 측정으로 확인하지 않았다.

## 8. 알려진 한계와 후속 작업

- **DOCX의 Heading만 있는 구간**은 지금도 Heading 한 줄짜리 청크가 된다. MD는 합치지만 DOCX는 이번 범위가 아니라 그대로 뒀다. 후속 "작은 구간 병합" 이슈에서 함께 처리한다.
- 청크 경계는 바뀌지 않았다. 문장 중간에서 끊기는 문제는 후속 이슈다.
- PDF는 구조 정보가 없어 효과가 없다. 후속 "PDF 제목 인식"에서 다룬다.
- DOCX Heading 스타일 ID가 `Heading1` 형태가 아닌 문서(예: 일부 한글화된 스타일)는 Heading으로 인식하지 못할 수 있다. 실제 업로드 문서 샘플로 확인하지 못했다.
- 검색 결과의 섹션을 화면에 표시하는 것은 프런트엔드 작업이며 이번 범위에 없다. RAG 프롬프트에 섹션 경로를 넣는 것도 하지 않았다.
- 작업 중 `develop`에서 임베딩 서버·Ollama로 가는 요청이 JSON이 아닌 XML로 전송되는 문제를 발견했다. 임베딩 쪽은 같은 날 팀원이 먼저 반영했고, Ollama 쪽은 이 브랜치에서 고쳤다 (§10).

## 9. 검증

### 9.1 자동 테스트

신규·수정한 테스트와 전체 결과는 아래와 같다.

| 구분 | 테스트 | 검증 내용 |
|---|---|---|
| 신규 | `SectionPathTest` (8) | 직렬화·읽기·표기·임베딩 입력, 손상 Metadata, 길이 제한, 보충 문자 경계 |
| 신규 | `MarkdownDocumentParserTest` (10) | 계층 경로, 원문 복원 계약, 본문 없는 Heading 병합, 코드 블록, 닫는 `#`, 닫는 `#`만 있는 빈 제목, 레벨 건너뜀, 오류 전파 |
| 추가 | `DocxDocumentParserTest` (+2) | 레벨별 경로, 같은·높은 레벨의 경로 닫힘, Title 루트, Heading 이전 본문 |
| 신규 | `SectionPathBenchmarkCorpusTest` (3) | 정답 유일성, 결정성, DOCX·MD 경로 일치 |
| 추가 | `DocumentEmbeddingTransactionServiceTest` (+2) | 경로가 있는 청크만 `embeddingText` 구성, 원문 검증 통과 |
| 추가 | `DocumentEmbeddingGeneratorTest` (+1), `AdaptiveEmbeddingBatchPlannerTest` (+1) | `embeddingText` 전송, 글자 예산 반영 |
| 신규 | `VectorSearchCandidateTest` (5) | 신규 검색·저장 결과·출처 재조회 모두 같은 경로 |
| 신규 | `VectorSearchRepositoryIntegrationTest` (1, 실제 PostgreSQL) | 벡터 검색 쿼리가 섹션 컬럼을 실제로 반환 |
| 수정 | `TextDocumentParserTest`, `DocumentParserRegistryTest` | TXT·MD 파서 분리 반영 |
| 수정 | `SearchConversationQueryServiceTest`, `DocGridMcpToolsTest`, `VectorSearchQueryServiceTest` | 새 필드 반영과 `sectionPath` 전달 단언 |
| 신규 | `EmbeddingClientWireContractTest` (2), `OllamaClientWireContractTest` (1) | 운영 설정으로 만든 RestClient가 로컬 HTTP 서버에 실제로 보내는 Content-Type·본문 (§10) |
| 추가·수정 | `FileValidationServiceTest`, `DocumentChunkTransactionServiceTest` | `.md` MIME 허용 확대와 청킹 단계 통과 (§11) |

전체 결과: `./backend/gradlew -p backend test` **1360개 통과, 실패 0, 건너뜀 0** (`develop` 최신(`ce3fdcf`)을 합친 상태, CodeRabbit 리뷰 반영 후).
클래스별 결과는 [`full-test-summary.txt`](../test-results/evidence/issue-404/full-test-summary.txt)에 있다.
임시 PostgreSQL(pgvector 0.8.1)과 Valkey 컨테이너를 쓰고 개발 DB와 분리했다. Pub/Sub 테스트가 조용히 건너뛰지 않도록 `DOCGRID_TEST_REDIS_PORT`를 지정했다.
`develop` 합류 전 이 브랜치만으로는 1332개(변경 전 1297개 + 섹션 경로 32개 + 전송 계약 3개)가 통과했다.

- **간헐적 실패**: 전체 실행 중 이번 변경과 무관한 통합 테스트가 두 번 실패한 적이 있다.
  `RagResponseClaimIntegrationTest`(SKIP LOCKED 동시성, `NoSuchElementException`), 그리고 `develop` 합류 후 첫 실행의
  `StompSessionLifecycleIntegrationTest`(3초 대기 시간 초과)와 `DocumentIndexingCompletionIntegrationTest`(테스트 초기화 `TRUNCATE`의 DB 교착).
  모두 같은 클래스를 단독으로 3번 다시 돌렸을 때 통과했고 전체 재실행도 통과했다. 원인은 규명하지 못했고, 동시 실행 부하에서 간헐적으로 실패하는 테스트로 보고 있다.
- **합류 중 발견한 컴파일 오류**: `develop`에서 `EmbeddingProviderCircuitBreaker` 생성자에 `EmbeddingProviderCircuitMetrics` 인자가 추가돼
  이 브랜치의 테스트 2개(`EmbeddingClientWireContractTest`, `SectionPathQualityBenchmark`)가 컴파일되지 않았다. 기존 벤치마크와 같은 방식으로 맞췄다.

### 9.2 재현 방법

```bash
# 단위·통합 테스트 (DB는 pgvector 컨테이너, 로컬 .env의 DB 값이 섞이지 않게 환경변수로 덮어쓴다)
DB_HOST=127.0.0.1 DB_PORT=55432 DB_NAME=app DB_USER=app DB_PASSWORD=local_password \
  ./backend/gradlew -p backend test

# 검색 품질 벤치마크 (실제 BGE-M3 Embedding Server가 http://localhost:8000에서 실행 중이어야 함)
./backend/gradlew -p backend sectionPathQualityTest
# 결과: backend/build/reports/section-path/section-path-latest.json
```

## 10. 함께 수정한 문제: 임베딩·Ollama 요청이 XML로 전송됨

섹션 경로 작업과는 별개의 문제이며 이 브랜치에서 로컬 확인 중에 발견해 함께 고쳤다. 커밋은 분리한다.

### 10.1 증상

로컬에서 앱을 띄워 `POST /search`를 호출하면 `SEARCH-006`(임베딩 서버가 요청 계약을 거부했습니다)이 발생했다.
`EmbeddingClient`가 임베딩 서버에서 `422 UnprocessableEntity`를 받았다.

### 10.2 원인

- 2026-10-02에 합쳐진 GCS 어댑터(`fefe7a9`)가 `google-cloud-storage`를 추가했고, 이 라이브러리가 `jackson-dataformat-xml`을 클래스패스에 끌어온다.
- `RestClient.builder()`로 만든 클라이언트는 `.body(record)`를 보낼 때 Content-Type을 지정하지 않으면 기본 변환기 중 XML 변환기를 먼저 고른다.
  임베딩 클라이언트와 Ollama 클라이언트는 Content-Type을 지정하지 않았고, 로컬 HTTP 서버로 확인하면 `Content-Type: application/xml;charset=UTF-8`이 전송된다.
- 임베딩 서버(FastAPI)는 XML 본문을 거부해 422를 반환한다. Ollama는 오류 응답으로 RAG가 **오류 없이 추출형 Fallback으로 대체**돼서 증상이 드러나지 않았을 가능성이 크다.
- 기존 단위 테스트는 `RestClient`를 mock으로 대체해서 실제 전송 형식을 볼 수 없었다. 그래서 `develop`에서 이틀간 잡히지 않았다.
- `-Dspring.xml.ignore=true`는 `RestClient`에 효과가 없어 우회 수단이 되지 않았다.

### 10.3 수정

세 요청(`EmbeddingClient.embed`, `EmbeddingClient.embedBatch`, `OllamaClient.generate`)에 `.contentType(MediaType.APPLICATION_JSON)`을 명시하는 것이 수정이다.
요청 단위로 명시하면 `RestClient`를 어떻게 만들든(운영 설정, 테스트, 벤치마크) 같은 결과가 나온다.

- **`EmbeddingClient`의 두 요청은 같은 날 팀원(김기민)이 `develop`에 먼저 반영했다** (`32ccc58`, "fix: send embedding requests as json").
  같은 수정이라 이 브랜치에서는 중복을 버리고 `develop`의 변경을 그대로 쓴다.
- **`OllamaClient.generate`는 `develop`에 아직 반영되지 않았고** 이 브랜치에서 고쳤다.
- 전송 계약 테스트(`EmbeddingClientWireContractTest`, `OllamaClientWireContractTest`)는 이 브랜치에서 추가했다. 기존 단위 테스트가 RestClient를 mock으로 대체해서 놓쳤던 부분이다.

### 10.4 검증

| 단계 | 결과 |
|---|---|
| RED | 수정 전 코드에서 신규 전송 계약 테스트 3개가 모두 실패했다. 실제로 받은 값은 `application/xml;charset=UTF-8`이었다. (`develop`의 임베딩 수정을 합친 뒤에는 임베딩 2개가 통과하고 Ollama 1개만 이 브랜치의 수정이 필요하다) |
| GREEN | 수정 후 3개 통과. 기존 `EmbeddingClientTest`(19), `OllamaClientTest`(15)도 통과했다. |
| 실제 서버 | 로컬 Embedding Server(BGE-M3)에 단건 요청은 1024차원 벡터, Batch 요청은 모델 `BAAI/bge-m3` 2건을 받았다. 로컬 Ollama(`qwen2.5:7b`)는 정상 답변을 반환했다. 운영 설정(`EmbeddingServerConfig`, `OllamaServerConfig`)으로 만든 클라이언트를 사용했다. |
| 벤치마크 | 어제 넣었던 JSON 헤더 우회를 제거하고 다시 측정해도 §7.2와 같은 수치가 나왔다. |
| 전체 | `develop` 합류 후 1360개 통과 (§9.1) |

### 10.5 남은 위험

- 이 수정은 호출 지점 세 곳만 고친다. 앞으로 같은 방식의 `RestClient` 호출을 추가하면서 Content-Type을 빠뜨리면 다시 생길 수 있다.
  신규 호출은 전송 계약 테스트처럼 실제 서버로 검증하는 것이 안전하다.
- 앱 전체(업로드부터 검색까지)를 실제 서버로 끝까지 실행해 확인한 것은 아니다. 클라이언트 단위의 실제 서버 확인과 전체 테스트까지만 했다.
- 서비스 기동 시점에 영향을 받던 다른 사용자(팀원)가 있을 수 있다. 이 수정이 `develop`에 반영되기 전까지 같은 증상이 계속된다.

## 11. MD 업로드 MIME 허용 확대

### 11.1 증상

앱에서 `.md` 파일을 올리면 "지원하지 않는 파일 형식입니다."(`DOCUMENT-FILE-004`)로 거부됐다. 확장자(`.md`)는 통과했고 브라우저가 붙인 MIME 타입이 허용 목록에 없었다.

### 11.2 원인

`.md`는 브라우저와 OS마다 붙이는 MIME 타입이 다르다. 허용 목록은 `text/markdown`, `text/plain` 두 개뿐이었다.
실제로 올린 환경(브라우저)이 보낸 타입은 **`text/x-markdown`**이었다 (업로드된 문서의 `content_type`에서 확인).

### 11.3 수정

`.md`에 한해 허용 목록을 `text/markdown`, `text/x-markdown`, `text/plain`, `application/octet-stream`으로 넓혔다.
`application/octet-stream`은 브라우저가 타입을 모를 때의 기본값이다.

- 업로드 때 저장된 MIME 타입을 청킹 단계(`DocumentChunkTransactionService.validateSupportedFile`)가 다시 검사한다. 두 곳의 목록이 어긋나면 업로드는 통과하고 청킹에서 막히므로
  `FileValidationService.MARKDOWN_CONTENT_TYPES` 상수 하나를 두 곳이 공유한다.
- TXT, PDF, DOCX의 허용 목록은 바꾸지 않았다. `.txt`와 `.pdf`에 `application/octet-stream`은 계속 거부한다.
- **기존 규칙을 의도적으로 뒤집었다.** 기존 `FileValidationServiceTest`에 `sample.md` + `application/octet-stream`이 거부돼야 한다는 단언이 있었다.
  이 단언을 `application/pdf`(거부)로 바꾸고 허용 케이스 테스트를 추가했다.
- 확장자 검사와 엄격한 UTF-8 디코딩이 이미 있어 허용 범위를 넓혀도 위험은 크지 않다고 판단했다. 정책 변경이므로 리뷰에서 확인이 필요하다.

### 11.4 검증

실제 앱에서 `.md` 샘플을 업로드해 `INDEXED`까지 확인했고, 청크 9개와 섹션 경로가 기대값과 일치했다 (document id 16, `text/x-markdown`으로 업로드).
