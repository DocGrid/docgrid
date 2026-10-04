package com.opensource.docgrid.domain.search.service.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.document.service.SectionPath;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.search.dto.ConversationCitationProjection;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.dto.ConversationQueryProjection;
import com.opensource.docgrid.domain.search.dto.ConversationRagResponseProjection;
import com.opensource.docgrid.domain.search.dto.ConversationSearchResultProjection;
import com.opensource.docgrid.domain.search.dto.response.CitationResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchConversationResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchConversationSummaryResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchResultItem;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 사용자 대화방 목록·상세와 후속 질문용 제한된 최근 문맥을 조회한다.
 *
 * <p>API 상세는 최근 50개 Turn의 질문·검색 결과·RAG 답변·citation을 종류별 일괄 조회해
 * queryId로 조립한다. 내부 문맥은 호출자가 요청한 소수 Turn으로 제한하며 다른 사용자의
 * 대화방은 항상 찾을 수 없는 것으로 처리한다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class SearchConversationQueryService {

    private static final int RETRIEVAL_CONTEXT_TURNS = 2;
    private static final int CONVERSATION_DETAIL_TURNS = 50;

    private final SearchConversationRepository searchConversationRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final RagResponseRepository ragResponseRepository;
    private final SearchResultRepository searchResultRepository;
    private final ResponseCitationRepository responseCitationRepository;

    /**
     * 사용자의 검색 대화방을 최근 활동 순서로 페이지 조회한다.
     */
    public PageResponse<SearchConversationSummaryResponse> getConversations(Long userId, int page, int size) {
        // 1. 사용자 소유 대화방만 lastMessageAt 내림차순으로 조회한다.
        Page<SearchConversation> conversations = searchConversationRepository
            .findByUser_IdOrderByLastMessageAtDesc(userId, PageRequest.of(page, size));

        // 2. Entity가 트랜잭션 밖으로 나가지 않도록 요약 DTO와 공통 페이지 응답으로 변환한다.
        List<SearchConversationSummaryResponse> content = conversations.getContent().stream()
            .map(SearchConversationSummaryResponse::from)
            .toList();
        return PageResponse.from(conversations, content);
    }

    /**
     * 사용자 소유 대화방의 최근 질문과 저장된 답변을 시간순 Turn 목록으로 조회한다.
     */
    public SearchConversationResponse getConversation(Long conversationId, Long userId) {
        // 1. 다른 사용자의 대화방 존재를 노출하지 않도록 ID와 소유자를 함께 검증한다.
        SearchConversation conversation = ownedConversation(conversationId, userId);

        // 2. 최신 질문 50개의 표시 필드만 조회하고 화면 표시용 과거→현재 순서로 뒤집는다.
        List<ConversationQueryProjection> queries = new ArrayList<>(
            searchQueryRepository.findConversationDetailQueries(
                conversationId, PageRequest.of(0, CONVERSATION_DETAIL_TURNS)
            )
        );
        Collections.reverse(queries);
        if (queries.isEmpty()) {
            return new SearchConversationResponse(conversation.getId(), conversation.getTitle(), List.of());
        }

        // 3. Turn별 단건 조회를 반복하지 않도록 queryId를 모아 결과·RAG·citation을 각각 한 번에 읽는다.
        List<Long> queryIds = queries.stream().map(ConversationQueryProjection::queryId).toList();
        Map<Long, List<ConversationSearchResultProjection>> resultsByQueryId = searchResultRepository
            .findConversationDetailResults(queryIds)
            .stream()
            .collect(Collectors.groupingBy(
                ConversationSearchResultProjection::queryId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        Map<Long, ConversationRagResponseProjection> ragByQueryId = ragResponseRepository
            .findConversationDetailResponses(queryIds)
            .stream()
            .collect(Collectors.toMap(ConversationRagResponseProjection::queryId, Function.identity()));
        Map<Long, List<CitationResponse>> citationsByQueryId = responseCitationRepository
            .findConversationDetailCitations(queryIds)
            .stream()
            .collect(Collectors.groupingBy(
                ConversationCitationProjection::queryId,
                LinkedHashMap::new,
                Collectors.mapping(this::toCitationResponse, Collectors.toList())
            ));

        // 4. queryId별로 그룹화한 값을 결합해 추가 SQL 없이 불변 Turn 응답을 만든다.
        List<SearchConversationResponse.Turn> turns = queries.stream()
            .map(query -> new SearchConversationResponse.Turn(
                query.queryId(), query.queryText(), query.createdAt(),
                toSearchResponse(conversation.getId(), query.queryId(), resultsByQueryId,
                    ragByQueryId, citationsByQueryId)
            ))
            .toList();
        return new SearchConversationResponse(conversation.getId(), conversation.getTitle(), turns);
    }

    /** rankNo 정렬 결과를 기존 단건 조회 API처럼 빈틈없는 1-based 순위로 변환한다. */
    private List<SearchResultItem> toSearchResultItems(List<ConversationSearchResultProjection> results) {
        List<SearchResultItem> items = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            ConversationSearchResultProjection result = results.get(index);
            items.add(new SearchResultItem(
                index + 1, result.documentId(), result.chunkId(), result.documentTitle(),
                result.chunkText(), result.pageNo(),
                SectionPath.display(result.metadataJson(), result.sectionTitle()),
                result.similarityScore()
            ));
        }
        return List.copyOf(items);
    }

    /** Citation Projection을 기존 단건 조회 API와 같은 응답 필드로 변환한다. */
    private CitationResponse toCitationResponse(ConversationCitationProjection citation) {
        return new CitationResponse(
            citation.citationLabel(), citation.documentId(), citation.documentTitle(), citation.chunkId(),
            citation.pageNo(), SectionPath.display(citation.metadataJson(), citation.sectionTitle()),
            citation.quotedText()
        );
    }

    /** RAG가 아직 없거나 처리 중이면 기존 계약대로 PROCESSING 응답과 빈 citation을 반환한다. */
    private SearchResponse toSearchResponse(
        Long conversationId,
        Long queryId,
        Map<Long, List<ConversationSearchResultProjection>> resultsByQueryId,
        Map<Long, ConversationRagResponseProjection> ragByQueryId,
        Map<Long, List<CitationResponse>> citationsByQueryId
    ) {
        List<SearchResultItem> results = toSearchResultItems(resultsByQueryId.getOrDefault(queryId, List.of()));
        ConversationRagResponseProjection rag = ragByQueryId.get(queryId);
        if (rag == null || rag.status() == ResultStatus.PROCESSING) {
            return new SearchResponse(conversationId, queryId, results, ResultStatus.PROCESSING, null, List.of());
        }
        return new SearchResponse(
            conversationId, queryId, results, rag.status(), rag.answerText(),
            citationsByQueryId.getOrDefault(queryId, List.of())
        );
    }

    /**
     * 현재 질문을 제외한 최근 대화 Turn을 후속 검색·답변 문맥용 과거→현재 순서로 반환한다.
     */
    public List<ConversationContext> findRecentContext(
        Long conversationId,
        Long excludedQueryId,
        int maxTurns
    ) {
        // 1. 최신순으로 제한 조회해 필요한 Turn 수 이상을 메모리에 적재하지 않는다.
        List<ConversationContext> context = new ArrayList<>(ragResponseRepository.findRecentConversationContext(
            conversationId, excludedQueryId, PageRequest.of(0, maxTurns)
        ));

        // 2. Prompt와 검색어가 자연스러운 시간 순서를 따르도록 과거→현재로 뒤집어 고정한다.
        Collections.reverse(context);
        return List.copyOf(context);
    }

    /**
     * 짧은 이전 질문 문맥을 현재 질문 앞에 붙여 대명사형 후속 질문의 검색 의미를 보완한다.
     *
     * <p>답변 본문은 검색어를 과도하게 확장할 수 있어 포함하지 않고, 가장 최근 질문 두 개만 사용한다.
     */
    public String contextualizeRetrieval(String queryText, List<ConversationContext> context) {
        if (context.isEmpty()) return queryText;
        List<ConversationContext> recent = context.size() > RETRIEVAL_CONTEXT_TURNS
            ? context.subList(context.size() - RETRIEVAL_CONTEXT_TURNS, context.size())
            : context;
        String previousQuestions = recent.stream()
            .map(ConversationContext::queryText)
            .reduce((first, second) -> first + " / " + second)
            .orElse("");
        return "이전 질문: %s\n현재 질문: %s".formatted(previousQuestions, queryText);
    }

    /**
     * ID와 사용자 ID를 함께 조회해 다른 사용자의 대화방을 동일한 Not Found로 숨긴다.
     */
    private SearchConversation ownedConversation(Long conversationId, Long userId) {
        return searchConversationRepository.findByIdAndUser_Id(conversationId, userId)
            .orElseThrow(() -> new DocGridException(ErrorCode.SEARCH_CONVERSATION_NOT_FOUND));
    }
}
