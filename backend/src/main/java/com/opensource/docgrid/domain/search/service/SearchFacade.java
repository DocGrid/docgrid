package com.opensource.docgrid.domain.search.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.service.query.QueryEmbeddingService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.config.HybridSearchProperties;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.dto.SearchAdmission;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.dto.response.SearchResponse;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.service.command.SearchQueryCommandService;
import com.opensource.docgrid.domain.search.service.command.SearchResultCommandService;
import com.opensource.docgrid.domain.search.service.query.AccessibleDocumentQueryService;
import com.opensource.docgrid.domain.search.service.query.HybridSearchQueryService;
import com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 검색 전체 흐름을 조율하는 Facade (F-SEARCH-05/06/07).
 *
 * <pre>
 * 1. User·Collection 검증과 소유 대화방·search_queries PROCESSING 원장 커밋
 * 2. 최근 질문 문맥 구성
 * 3. Transaction 밖에서 검색어 임베딩
 * 4. 권한 pre-filter → 접근 가능한 document_id 목록 (빈 목록이면 skip)
 * 5. pgvector Top-K 후보 추출
 * 6. live check — 캐시 stale 방어
 * 7. search_results 저장 + search_queries SUCCESS 원자적 확정
 * 8. 실패 시 독립 Transaction으로 search_queries FAILED 확정
 * </pre>
 *
 * <p>stale 캐시 방어 시나리오(권한 회수 직후 검색)는 6단계 live check 타이밍 로그로 추적 가능하다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchFacade {

    private final QueryEmbeddingService queryEmbeddingService;
    private final SearchConversationQueryService searchConversationQueryService;
    private final SearchQueryCommandService searchQueryCommandService;
    private final AccessibleDocumentQueryService accessibleDocumentQueryService;
    private final VectorSearchQueryService vectorSearchQueryService;
    private final HybridSearchQueryService hybridSearchQueryService;
    private final HybridSearchProperties hybridSearchProperties;
    private final PermissionQueryService permissionQueryService;
    private final SearchResultCommandService searchResultCommandService;

    public SearchOutcome search(Long userId, SearchRequest request) {
        long start = System.currentTimeMillis();

        // 1. 외부 호출 전에 대화방 변경과 PROCESSING 원장을 짧은 Transaction으로 커밋한다.
        SearchAdmission admission = searchQueryCommandService.createProcessing(
            userId, request.conversationId(), request.collectionId(),
            request.queryText(), request.effectiveTopK()
        );

        Long queryId = admission.queryId();

        try {
            // 2. 방금 만든 Query는 제외하고 최근 질문만 검색 Vector 문맥에 포함한다.
            List<ConversationContext> context = searchConversationQueryService.findRecentContext(
                admission.conversationId(), queryId, 2
            );
            String retrievalText = searchConversationQueryService.contextualizeRetrieval(request.queryText(), context);

            // 3. 임베딩 HTTP 대기 동안 DB Transaction과 Connection을 점유하지 않는다.
            EmbedResult embedResult = queryEmbeddingService.embed(retrievalText);

            // 4. 이 사용자가 볼 수 있는 문서 ID만 미리 추림
            List<Long> permittedIds = accessibleDocumentQueryService
                .findReadableDocumentIds(userId, request.collectionId());

            /*
             * 볼 수 있는 문서가 하나도 없으면 벡터 검색 자체를 생략한다.
             * 권한 문제로 결과가 없는 것은 실패가 아니라 정상 케이스이므로 빈 결과와 SUCCESS를
             * 함께 확정하고, 200 + 빈 결과로 응답한다.
             */
            if (permittedIds.isEmpty()) {
                log.info("[SEARCH] no accessible documents userId={}", userId);
                int latency = latencyMs(start);
                searchResultCommandService.saveAllAndComplete(
                    queryId, embedResult.model(), embedResult.vector(), List.of(), latency
                );
                return new SearchOutcome(
                    SearchResponse.empty(admission.conversationId(), queryId), List.of(), List.of()
                );
            }

            // 5. 후보 추출 (F-SEARCH-05). 하이브리드가 켜져 있으면 벡터 유사도에 질문 단어 일치(희귀할수록 비중 큼)를
            //    보너스로 더해 순위를 매기고, 꺼져 있으면 기존 pgvector 단독 검색을 그대로 쓴다.
            //    단어는 문맥이 섞이지 않은 원문 질문에서 뽑는다.
            List<VectorSearchCandidate> candidates = hybridSearchProperties.isEnabled()
                ? hybridSearchQueryService.search(
                    embedResult.vector(), request.queryText(), embedResult.model().getId(),
                    permittedIds, request.effectiveTopK())
                : vectorSearchQueryService.search(
                    embedResult.vector(), embedResult.model().getId(),
                    permittedIds, request.effectiveTopK());

            // 6. live check — 캐시 stale 방어 (F-SEARCH-06)
            long liveStart = System.currentTimeMillis();
            List<VectorSearchCandidate> verified = candidates.stream()
                .filter(c -> permissionQueryService.canReadDocument(userId, c.documentId()))
                .toList();
            log.info("[SEARCH] live check userId={} before={} after={} liveMs={}",
                userId, candidates.size(), verified.size(), latencyMs(liveStart));

            // 7. 검색 결과와 임베딩 정보, SUCCESS 상태를 하나의 짧은 Transaction으로 확정한다.
            int latency = latencyMs(start);
            List<SearchResult> savedResults = searchResultCommandService.saveAllAndComplete(
                queryId, embedResult.model(), embedResult.vector(), verified, latency
            );
            log.info("[SEARCH] done queryId={} results={} latencyMs={}", queryId, verified.size(), latency);

            return new SearchOutcome(
                SearchResponse.of(admission.conversationId(), queryId, verified), verified, savedResults
            );

        } catch (Exception e) {
            // 8. 성공 처리 Transaction이 끝난 뒤 커밋된 원장을 queryId로 FAILED 확정한다.
            searchQueryCommandService.markFailed(queryId, e.getMessage());
            throw e;
        }
    }

    private int latencyMs(long fromMillis) {
        return (int) (System.currentTimeMillis() - fromMillis);
    }
}
