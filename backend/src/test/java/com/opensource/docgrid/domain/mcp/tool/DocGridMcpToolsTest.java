package com.opensource.docgrid.domain.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.opensource.docgrid.domain.document.dto.response.CurrentVersionStatusResponse;
import com.opensource.docgrid.domain.document.dto.response.DocumentStatusResponse;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.domain.mcp.security.McpRateLimiter;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.dto.response.SearchResultItem;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocGridMcpTools 단위 테스트")
class DocGridMcpToolsTest {

    private static final Long USER_ID = 1L;

    private DocGridMcpTools docGridMcpTools;

    @Mock
    private SearchFacade searchFacade;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private PermissionQueryService permissionQueryService;

    @Mock
    private DocumentQueryService documentQueryService;

    // 사용자·도구별로 호출 횟수 카운터를 들고 있는 실제 인스턴스를 사용한다(목 아님).
    // @BeforeEach마다 새로 만들어 테스트 간 카운터가 공유되지 않도록 격리한다.
    private McpRateLimiter rateLimiter;

    // 실제 앱의 Spring 관리 ObjectMapper 빈과 동일하게 구성한다: JavaTimeModule 등록 + 날짜를 타임스탬프 배열이
    // 아닌 ISO 문자열로 직렬화 (Spring Boot의 Jackson 자동 설정 기본값과 동일하게 맞추지 않으면
    // LocalDateTime이 [2026,8,6,10,0] 같은 배열로 직렬화돼 실제 앱 동작과 달라진다)
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUpAuthentication() {
        // ObjectMapper는 실제 직렬화 결과를 검증해야 하므로 목이 아닌 실제 인스턴스를 사용한다
        rateLimiter = new McpRateLimiter();
        docGridMcpTools = new DocGridMcpTools(
                searchFacade, documentRepository, permissionQueryService, documentQueryService,
                rateLimiter, objectMapper);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("mcp-client", null, List.of());
        authentication.setDetails(USER_ID);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("정상 케이스: 유효한 query로 검색하면 SearchFacade 결과가 JSON으로 직렬화되어 반환된다")
    void searchDocuments_returnsJson_whenInputValid() {
        // Given
        SearchOutcome outcome = new SearchOutcome(SearchResponse.empty(1L), List.of(), List.of());
        given(searchFacade.search(eq(USER_ID), any(SearchRequest.class))).willReturn(outcome);

        // When
        String result = docGridMcpTools.searchDocuments("연차 규정", 5);

        // Then — searchDocuments는 SearchResponse 전체가 아니라 results 목록만 직렬화한다
        assertThat(result).isEqualTo("[]");
    }

    @Test
    @DisplayName("예외 케이스: query가 null이면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_queryNull() {
        assertThatThrownBy(() -> docGridMcpTools.searchDocuments(null, 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: query가 공백뿐이면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_queryBlank() {
        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("   ", 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: query가 2000자를 초과하면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_queryTooLong() {
        String tooLong = "a".repeat(2001);

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments(tooLong, 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: topK가 범위(1~20)를 벗어나면 INVALID_PARAMETER 예외가 발생한다")
    void searchDocuments_throws_when_topKOutOfRange() {
        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 21))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 0))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: 인증 정보가 없으면 UNAUTHORIZED 예외가 발생한다")
    void searchDocuments_throws_when_unauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("예외 케이스: 분당 20회를 초과하면 RATE_LIMIT_EXCEEDED 예외가 발생한다")
    void searchDocuments_throws_when_rateLimitExceeded() {
        SearchOutcome outcome = new SearchOutcome(SearchResponse.empty(1L), List.of(), List.of());
        given(searchFacade.search(eq(USER_ID), any(SearchRequest.class))).willReturn(outcome);

        for (int i = 0; i < 20; i++) {
            docGridMcpTools.searchDocuments("query", 5);
        }

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("예외 케이스: 예상하지 못한 예외는 내부 정보 없이 INTERNAL_SERVER_ERROR로 변환된다")
    void searchDocuments_throws_INTERNAL_SERVER_ERROR_when_unexpectedExceptionOccurs() {
        given(searchFacade.search(eq(USER_ID), any(SearchRequest.class)))
                .willThrow(new RuntimeException("DB 커넥션 풀 고갈 같은 내부 상세 정보"));

        assertThatThrownBy(() -> docGridMcpTools.searchDocuments("query", 5))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INTERNAL_SERVER_ERROR)
                .hasMessage(ErrorCode.INTERNAL_SERVER_ERROR.getMessage())
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("커넥션 풀"));
    }

    @Test
    @DisplayName("정상 케이스: chunkText가 1000자를 초과하면 잘려서 반환된다")
    void searchDocuments_truncatesChunkText_whenTooLong() {
        String longText = "가".repeat(1200);
        SearchResultItem item = new SearchResultItem(1, 10L, 20L, "문서", longText, null, "1장 > 1.1절", BigDecimal.ONE);
        SearchOutcome outcome = new SearchOutcome(
                new SearchResponse(1L, List.of(item), ResultStatus.PROCESSING, null, List.of()), List.of(), List.of());
        given(searchFacade.search(eq(USER_ID), any(SearchRequest.class))).willReturn(outcome);

        String result = docGridMcpTools.searchDocuments("query", 5);

        assertThat(result).contains("\"chunkText\":\"" + "가".repeat(1000) + "\"")
                .contains("\"documentId\":10")
                .contains("\"chunkId\":20")
                .contains("\"sectionPath\":\"1장 > 1.1절\"")
                .doesNotContain("가".repeat(1001));
    }

    @Test
    @DisplayName("정상 케이스: chunkText가 1000자 이내면 그대로 반환된다")
    void searchDocuments_keepsChunkText_whenWithinLimit() {
        String shortText = "짧은 청크 텍스트";
        SearchResultItem item = new SearchResultItem(1, 10L, 20L, "문서", shortText, null, null, BigDecimal.ONE);
        SearchOutcome outcome = new SearchOutcome(
                new SearchResponse(1L, List.of(item), ResultStatus.PROCESSING, null, List.of()), List.of(), List.of());
        given(searchFacade.search(eq(USER_ID), any(SearchRequest.class))).willReturn(outcome);

        String result = docGridMcpTools.searchDocuments("query", 5);

        assertThat(result).contains("\"chunkText\":\"" + shortText + "\"");
    }

    @Test
    @DisplayName("정상 케이스: 권한이 있으면 문서 상세 정보가 JSON으로 반환된다")
    void getDocumentDetail_returnsJson_whenValid() {
        // Given
        Document document = createDocument();
        given(permissionQueryService.canReadDocument(USER_ID, 1L)).willReturn(true);
        given(documentRepository.findByIdWithCurrentVersion(1L)).willReturn(Optional.of(document));

        // When
        String result = docGridMcpTools.getDocumentDetail(1L);

        // Then
        assertThat(result).contains("\"documentId\":1")
                .contains("\"title\":\"테스트 문서\"")
                .contains("\"currentVersionNo\":3")
                .contains("\"status\":\"INDEXED\"")
                .contains("\"updatedAt\":\"2026-08-06T10:00:00\"");
    }

    @Test
    @DisplayName("예외 케이스: documentId가 없으면 INVALID_PARAMETER 예외가 발생한다")
    void getDocumentDetail_throws_when_documentIdNull() {
        assertThatThrownBy(() -> docGridMcpTools.getDocumentDetail(null))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: 권한이 없으면 PERMISSION_DENIED 예외가 발생한다")
    void getDocumentDetail_throws_when_permissionDenied() {
        given(permissionQueryService.canReadDocument(USER_ID, 1L)).willReturn(false);

        assertThatThrownBy(() -> docGridMcpTools.getDocumentDetail(1L))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("예외 케이스: 문서가 없으면 DOCUMENT_NOT_FOUND 예외가 발생한다")
    void getDocumentDetail_throws_when_documentNotFound() {
        given(permissionQueryService.canReadDocument(USER_ID, 1L)).willReturn(true);
        given(documentRepository.findByIdWithCurrentVersion(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> docGridMcpTools.getDocumentDetail(1L))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("예외 케이스: 문서가 삭제됐으면 DOCUMENT_NOT_FOUND 예외가 발생한다")
    void getDocumentDetail_throws_when_documentDeleted() {
        Document document = Document.builder()
                .title("삭제된 문서")
                .status(DocumentStatus.DELETED)
                .build();
        ReflectionTestUtils.setField(document, "id", 1L);
        given(permissionQueryService.canReadDocument(USER_ID, 1L)).willReturn(true);
        given(documentRepository.findByIdWithCurrentVersion(1L)).willReturn(Optional.of(document));

        assertThatThrownBy(() -> docGridMcpTools.getDocumentDetail(1L))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("예외 케이스: 인증 정보가 없으면 UNAUTHORIZED 예외가 발생한다")
    void getDocumentDetail_throws_when_unauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> docGridMcpTools.getDocumentDetail(1L))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("정상 케이스: 현재 버전이 없으면 currentVersionNo 필드 자체가 응답에서 빠진다")
    void getDocumentDetail_excludesNullField_whenCurrentVersionMissing() {
        Document document = Document.builder()
                .title("버전 없는 문서")
                .status(DocumentStatus.DRAFT)
                .build();
        ReflectionTestUtils.setField(document, "id", 1L);
        ReflectionTestUtils.setField(document, "updatedAt", LocalDateTime.of(2026, 8, 6, 10, 0));
        given(permissionQueryService.canReadDocument(USER_ID, 1L)).willReturn(true);
        given(documentRepository.findByIdWithCurrentVersion(1L)).willReturn(Optional.of(document));

        String result = docGridMcpTools.getDocumentDetail(1L);

        assertThat(result).doesNotContain("currentVersionNo");
    }

    @Test
    @DisplayName("정상 케이스: DocumentQueryService 결과가 JSON으로 반환된다")
    void getIndexingStatus_returnsJson_whenValid() {
        // Given
        DocumentStatusResponse response = new DocumentStatusResponse(
                1L, DocumentStatus.INDEXED,
                new CurrentVersionStatusResponse(3, DocumentVersionStatus.INDEXED),
                null
        );
        given(documentQueryService.getDocumentStatus(USER_ID, 1L)).willReturn(response);

        // When
        String result = docGridMcpTools.getIndexingStatus(1L);

        // Then
        assertThat(result).contains("\"documentStatus\":\"INDEXED\"")
                .contains("\"versionNo\":3")
                // processingVersion이 null이면 응답에서 필드 자체가 빠진다 — DocumentStatusResponse는
                // 우리 소유 파일이 아니라 @JsonInclude를 못 붙이지만, DocGridMcpTools 전용 ObjectMapper
                // 복사본 설정으로도 동일하게 적용됨을 확인한다
                .doesNotContain("processingVersion");
    }

    @Test
    @DisplayName("예외 케이스: documentId가 없으면 INVALID_PARAMETER 예외가 발생한다")
    void getIndexingStatus_throws_when_documentIdNull() {
        assertThatThrownBy(() -> docGridMcpTools.getIndexingStatus(null))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PARAMETER);
    }

    @Test
    @DisplayName("예외 케이스: 인증 정보가 없으면 UNAUTHORIZED 예외가 발생한다")
    void getIndexingStatus_throws_when_unauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> docGridMcpTools.getIndexingStatus(1L))
                .isInstanceOf(DocGridException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
    }

    private Document createDocument() {
        Document document = Document.builder()
                .title("테스트 문서")
                .status(DocumentStatus.INDEXED)
                .build();
        ReflectionTestUtils.setField(document, "id", 1L);
        ReflectionTestUtils.setField(document, "updatedAt", LocalDateTime.of(2026, 8, 6, 10, 0));

        DocumentVersion currentVersion = DocumentVersion.builder()
                .versionNo(3)
                .build();
        ReflectionTestUtils.setField(document, "currentVersion", currentVersion);
        return document;
    }
}
