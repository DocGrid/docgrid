package com.opensource.docgrid.domain.mcp.tool;

import java.util.List;
import java.util.function.Function;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.enums.DocumentStatus;
import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.document.service.query.DocumentQueryService;
import com.opensource.docgrid.domain.mcp.dto.response.DocumentDetailResponse;
import com.opensource.docgrid.domain.mcp.security.McpRateLimiter;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResultItem;
import com.opensource.docgrid.domain.search.service.SearchFacade;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * MCP 도구 3종({@code search_documents}, {@code get_document_detail}, {@code get_indexing_status})의
 * 핸들러. 새 비즈니스 로직을 만들지 않고 기존 서비스({@link SearchFacade}, {@link PermissionQueryService},
 * {@link DocumentQueryService})를 그대로 호출하는 얇은 어댑터다.
 *
 * <p>도구별 주요 입력·출력 제약:
 * <ul>
 *   <li>query 길이 제한: 2000자</li>
 *   <li>topK 범위: 1~20</li>
 *   <li>chunkText 길이 제한: 1000자 (검색 결과 반환 시)</li>
 *   <li>search_documents 호출 제한: 분당 20회</li>
 *   <li>get_document_detail / get_indexing_status 호출 제한: 분당 30회</li>
 * </ul>
 */
@Slf4j
@Component
public class DocGridMcpTools {

    private static final int MAX_QUERY_LENGTH = 2000;
    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_CHUNK_TEXT_LENGTH = 1000;

    private static final int SEARCH_RATE_LIMIT_PER_MINUTE = 20;
    private static final int DOCUMENT_RATE_LIMIT_PER_MINUTE = 30;

    private final SearchFacade searchFacade;
    private final DocumentRepository documentRepository;
    private final PermissionQueryService permissionQueryService;
    private final DocumentQueryService documentQueryService;
    private final McpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    /**
     * 기존 검색·문서·권한 Service와 MCP 전용 호출 제한 및 JSON 직렬화 경계를 연결한다.
     */
    public DocGridMcpTools(SearchFacade searchFacade, DocumentRepository documentRepository,
            PermissionQueryService permissionQueryService, DocumentQueryService documentQueryService,
            McpRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.searchFacade = searchFacade;
        this.documentRepository = documentRepository;
        this.permissionQueryService = permissionQueryService;
        this.documentQueryService = documentQueryService;
        this.rateLimiter = rateLimiter;
        /*
         * 1. MCP 응답은 null 필드를 제외한다. 앱 전체가 공유하는 ObjectMapper Bean을 직접 바꾸면
         * 다른 REST API 응답에도 영향을 주므로, 이 클래스 전용 복사본에만 설정을 적용한다.
         */
        this.objectMapper = objectMapper.copy().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    }

    @McpTool(name = "search_documents",
        description = "사용자 질문과 관련된 문서 chunk를 벡터 검색으로 찾는다. 권한이 있는 문서만 반환된다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String searchDocuments(
            @McpToolParam(description = "검색어", required = true) String query,
            @McpToolParam(description = "반환할 최대 결과 수 (기본 5, 1~20)", required = false) Integer topK) {

        /*
         * 1. SDK는 required(필수값)를 강제하지 않음이 실측으로 확인됨 (query=null로 그대로 호출됨)
         * → null/blank 여부와 비즈니스 규칙(길이/범위)을 전부 여기서 직접 검증한다
         */
        validateSearchInput(query, topK);

        // 2. 공통 인증·호출 제한·예외 변환 안에서 기존 권한 적용 검색 흐름을 실행한다.
        return executeTool("search_documents", SEARCH_RATE_LIMIT_PER_MINUTE, userId -> {
            // 3. 권한 pre-filter + live check는 SearchFacade 내부에서 수행하고 긴 Chunk만 응답 경계에서 줄인다.
            SearchOutcome outcome = searchFacade.search(userId, new SearchRequest(query, topK, null));
            return truncateChunkText(outcome.response().results());
        });
    }

    @McpTool(name = "get_document_detail",
        description = "특정 문서의 메타데이터와 현재 버전 정보를 조회한다. 권한이 있는 문서만 조회 가능하다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String getDocumentDetail(
            @McpToolParam(description = "문서 ID", required = true) Long documentId) {
        // 1. SDK 입력 검증과 별개로 필수 문서 식별자를 도구 경계에서 확인한다.
        requireDocumentId(documentId);

        // 2. 공통 인증·호출 제한·예외 변환 안에서 문서 조회를 수행한다.
        return executeTool("get_document_detail", DOCUMENT_RATE_LIMIT_PER_MINUTE, userId -> {
            // 3. 권한이 없으면 문서 존재 여부를 노출하지 않기 위해 원장 조회 전에 차단한다.
            if (!permissionQueryService.canReadDocument(userId, documentId)) {
                throw new DocGridException(ErrorCode.PERMISSION_DENIED);
            }

            /*
             * 4. title/status/currentVersion/updatedAt은 Document 엔티티에 이미 있어 직접 사용한다.
             * currentVersion은 LAZY라 OSIV가 꺼진 /mcp 경로에서는 findById만 쓰면 트랜잭션
             * 종료 후 LazyInitializationException이 나므로 JOIN FETCH 쿼리를 사용한다.
             */
            Document document = documentRepository.findByIdWithCurrentVersion(documentId)
                    .orElseThrow(() -> new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND));

            /*
             * 5. 소프트 삭제된 문서는 존재하지 않는 것과 동일하게 취급한다 — get_indexing_status가
             * 위임하는 DocumentQueryService.getDocumentStatus()와 동일한 처리.
             */
            if (document.getStatus() == DocumentStatus.DELETED) {
                throw new DocGridException(ErrorCode.DOCUMENT_NOT_FOUND);
            }

            // 6. 현재 검색 Version이 없는 업로드·인덱싱 중 문서에는 Version 번호를 null로 반환한다.
            Integer currentVersionNo = document.getCurrentVersion() != null
                    ? document.getCurrentVersion().getVersionNo()
                    : null;
            return new DocumentDetailResponse(
                    document.getId(), document.getTitle(), currentVersionNo,
                    document.getStatus(), document.getUpdatedAt()
            );
        });
    }

    @McpTool(name = "get_indexing_status",
        description = "특정 문서의 원장 상태와 현재 검색 가능 버전 및 처리 중 버전·Job 상태를 조회한다.",
        annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false))
    public String getIndexingStatus(
            @McpToolParam(description = "문서 ID", required = true) Long documentId) {
        // 1. SDK 입력 검증과 별개로 필수 문서 식별자를 도구 경계에서 확인한다.
        requireDocumentId(documentId);

        // 2. DocumentQueryService가 문서 존재·삭제·읽기 권한과 Version 상태 조합을 동일하게 처리한다.
        return executeTool("get_indexing_status", DOCUMENT_RATE_LIMIT_PER_MINUTE,
                userId -> documentQueryService.getDocumentStatus(userId, documentId));
    }

    /**
     * 도구 3종에 공통되는 실행 흐름(사용자 식별 → rate limit → 실행 → 안전한 예외 변환 → JSON 직렬화)을 담당한다.
     * DocGridException은 이미 안전한 메시지를 담고 있어 그대로 전파하고, 그 외 예상치 못한 예외는
     * 내부 정보가 클라이언트에 노출되지 않도록 INTERNAL_SERVER_ERROR로 치환한다.
     */
    private String executeTool(String toolName, int limitPerMinute, Function<Long, Object> action) {
        // 1. 운영 로그에 도구 실행 시간만 남길 수 있도록 시작 시각을 잡고 인증 사용자 ID를 복원한다.
        long startedAt = System.nanoTime();
        Long userId = currentUserId();

        try {
            // 2. 사용자·도구별 호출 한도를 먼저 검사한 뒤 실제 읽기 작업과 JSON 직렬화를 수행한다.
            rateLimiter.checkLimit(userId, toolName, limitPerMinute);
            Object result = action.apply(userId);
            String response = toJson(result);
            log.info("[MCP] tool={} userId={} outcome=SUCCESS elapsedMs={}",
                toolName, userId, elapsedMillis(startedAt));
            return response;
        } catch (DocGridException e) {
            // 3. Query, 문서 본문, Authorization Header와 Token 원문은 운영 로그에 절대 남기지 않는다.
            log.warn("[MCP] tool={} userId={} outcome=DENIED errorCode={} elapsedMs={}",
                toolName, userId, e.getErrorCode().getCode(), elapsedMillis(startedAt));
            throw e;
        } catch (Exception e) {
            // 4. 예상하지 못한 내부 예외는 구체 내용을 숨긴 공통 오류로 변환한다.
            log.error("[MCP] tool={} userId={} outcome=ERROR errorCode={} elapsedMs={}",
                toolName, userId, ErrorCode.INTERNAL_SERVER_ERROR.getCode(), elapsedMillis(startedAt));
            throw new DocGridException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /** 실행 시작부터 현재 시점까지의 경과 시간을 밀리초로 변환한다. */
    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /** 검색 도구의 JSON 응답 크기를 제한하도록 각 결과의 긴 Chunk Text를 잘라낸다. */
    private List<SearchResultItem> truncateChunkText(List<SearchResultItem> items) {
        return items.stream()
                .map(item -> item.chunkText() != null && item.chunkText().length() > MAX_CHUNK_TEXT_LENGTH
                        ? new SearchResultItem(item.rank(), item.documentId(), item.chunkId(), item.documentTitle(),
                                item.chunkText().substring(0, MAX_CHUNK_TEXT_LENGTH),
                                item.pageNo(), item.similarityScore())
                        : item)
                .toList();
    }

    /** 문서 도구가 받을 필수 양수 ID가 존재하는지 검증한다. */
    private void requireDocumentId(Long documentId) {
        if (documentId == null) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER, "documentId는 필수입니다.");
        }
    }

    /** MCP SDK가 강제하지 않는 검색어 필수값·길이와 topK 범위를 도구 경계에서 검증한다. */
    private void validateSearchInput(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER, "query는 필수입니다.");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER,
                    "query는 " + MAX_QUERY_LENGTH + "자 이내여야 합니다.");
        }
        if (topK != null && (topK < MIN_TOP_K || topK > MAX_TOP_K)) {
            throw new DocGridException(ErrorCode.INVALID_PARAMETER,
                    "topK는 " + MIN_TOP_K + "~" + MAX_TOP_K + " 사이여야 합니다.");
        }
    }

    /**
     * McpApiKeyAuthFilter가 Authentication details에 저장한 사용자 식별자를 꺼낸다.
     *
     * <p>MCP 인증은 일반 로그인 Principal이 아니라 API Key에 연결된 사용자 ID를 details로 전달한다.
     */
    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof Long userId)) {
            throw new DocGridException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    /** MCP 도구 결과를 null 필드가 제외된 JSON 문자열로 직렬화한다. */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new DocGridException(ErrorCode.INTERNAL_SERVER_ERROR, e);
        }
    }
}
