package com.opensource.docgrid.domain.rag.service;

import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 모델 직전의 짧은 Primary 트랜잭션에서 허용 후보·과거 문맥·전이적 출처를 준비한다.
 * 저장된 과거 promptText를 신뢰하지 않으며 외부 모델 HTTP는 담당하지 않는다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RagPromptPreparationService {
    private static final int MAX_CANDIDATES = 3;
    private final SearchQueryRepository queries;
    private final SearchResultRepository results;
    private final SearchConversationQueryService conversations;
    private final RagDocumentAccessService authorization;
    private final PromptBuilder promptBuilder;

    public PreparedPrompt prepare(Long queryId) {
        // 1. DB가 기록한 질문 소유자와 근거를 사용한다. 요청의 사용자/후보를 재신뢰하지 않는다.
        var query = queries.findById(queryId)
            .orElseThrow(() -> new DocGridException(ErrorCode.RAG_ANSWER_NOT_FOUND));
        Long userId = query.getUser().getId();
        List<SearchResult> saved = results.findByQuery_IdOrderByRankNo(queryId);
        var access = authorization.authorize(userId, saved.stream()
            .map(result -> result.getChunk().getDocumentVersion().getDocument().getId()).toList());
        List<SearchResult> allowed = saved.stream()
            .filter(result -> access.canRead(result.getChunk().getDocumentVersion().getDocument().getId()))
            .limit(MAX_CANDIDATES).toList();
        List<VectorSearchCandidate> candidates = allowed.stream().map(VectorSearchCandidate::from).toList();

        // 2. 이전 답변은 그 답변이 사용한 모든 출처를 현재도 읽을 수 있을 때만 포함한다.
        var history = conversations.findRecentContext(userId, query.getConversation().getId(), queryId, 3);
        var sources = new LinkedHashSet<Long>();
        candidates.forEach(candidate -> sources.add(candidate.documentId()));
        history.forEach(turn -> sources.addAll(authorization.sources(turn.sourceDocumentIdsJson())));
        return new PreparedPrompt(userId, promptBuilder.build(query.getQueryText(), candidates, history),
            authorization.encode(sources), candidates, allowed);
    }

    /** 외부 호출 중 Entity를 탐색하지 않도록 준비된 후보와 출처를 함께 고정한다. */
    public record PreparedPrompt(Long userId, String promptText, String sourceDocumentIdsJson,
                                 List<VectorSearchCandidate> candidates, List<SearchResult> searchResults) {
        public PreparedPrompt { candidates = List.copyOf(candidates); searchResults = List.copyOf(searchResults); }
    }
}
