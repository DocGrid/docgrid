package com.opensource.docgrid.domain.search.service.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import com.opensource.docgrid.domain.rag.service.RagDocumentAccessService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.response.CitationResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchResultItem;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 비동기 RAG 처리(#218)에서 프론트가 WebSocket push를 신호로 삼아 다시 조회하는 GET /search/{queryId}
 * 전용 조회 서비스. 저장 답변의 출처와 발췌를 현재 문서 권한으로 재검증하고 같은 응답 계약으로 반환한다 —
 * 프론트가 두 응답을 같은 타입으로 다룰 수 있게 하기 위함이다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class SearchAnswerQueryService {

    private final SearchQueryRepository searchQueryRepository;
    private final RagResponseRepository ragResponseRepository;
    private final SearchResultRepository searchResultRepository;
    private final ResponseCitationRepository responseCitationRepository;
    private final RagDocumentAccessService authorization;

    public SearchResponse getAnswer(Long queryId, Long userId) {
        // 1. 소유권 검증 — 본인 것이 아니면 존재 자체를 숨긴다(404).
        SearchQuery query = searchQueryRepository.findByIdAndUser_Id(queryId, userId)
            .orElseThrow(() -> new DocGridException(ErrorCode.RAG_ANSWER_NOT_FOUND));

        // 2. 발췌·답변·인용의 전체 출처를 현재 Primary에서 재인가한다.
        List<SearchResult> savedResults = searchResultRepository.findByQuery_IdOrderByRankNo(query.getId());
        RagResponse ragResponse = ragResponseRepository.findByQuery_Id(query.getId()).orElse(null);
        var citations = ragResponse == null ? List.<CitationResponse>of() : responseCitationRepository
            .findByResponse_IdOrderByCitationOrder(ragResponse.getId()).stream().map(CitationResponse::from).toList();
        List<Long> documentIds = new ArrayList<>(savedResults.stream()
            .map(result -> result.getChunk().getDocumentVersion().getDocument().getId()).toList());
        citations.forEach(citation -> documentIds.add(citation.documentId()));
        var access = authorization.authorize(userId, documentIds,
            Collections.singletonList(ragResponse == null ? null : ragResponse.getSourceDocumentIdsJson()));

        // 3. 허용 발췌의 순위를 다시 매기고, 생성 중이면 원문 답변을 반환하지 않는다.
        List<SearchResultItem> items = new ArrayList<>();
        for (SearchResult result : savedResults) {
            var candidate = VectorSearchCandidate.from(result);
            if (access.canRead(candidate.documentId())) items.add(SearchResultItem.of(items.size() + 1, candidate));
        }
        if (ragResponse == null || ragResponse.getStatus() == ResultStatus.PROCESSING) {
            return new SearchResponse(query.getConversation().getId(), queryId, items,
                ResultStatus.PROCESSING, null, List.of());
        }

        // 4. 답변 일부를 제거해 안전하다고 추정하지 않고 출처 하나라도 금지되면 전체를 보류한다.
        boolean permitted = authorization.permits(access, ragResponse.getSourceDocumentIdsJson());
        return new SearchResponse(query.getConversation().getId(), queryId, items, ragResponse.getStatus(),
            permitted ? ragResponse.getAnswerText() : RagDocumentAccessService.WITHHELD_ANSWER,
            permitted ? citations.stream().filter(citation -> access.canRead(citation.documentId())).toList() : List.of());
    }
}
