package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.permission.service.query.PrimaryDocumentPermissionQueryService;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService;

/** 실제 프롬프트 준비에서 현재 권한을 먼저 적용하고 전이적 출처와 후보 상한을 함께 보존하는지 검증한다. */
class RagPromptPreparationServiceTest {
    @Test
    void reauthorizesBeforeSelectingThreeAndKeepsHistoryProvenance() {
        var queries = mock(SearchQueryRepository.class);
        var results = mock(SearchResultRepository.class);
        var history = mock(SearchConversationQueryService.class);
        var permissions = mock(PrimaryDocumentPermissionQueryService.class);
        var authorization = new RagDocumentAccessService(permissions, new ObjectMapper());
        var service = new RagPromptPreparationService(queries, results, history, authorization, new PromptBuilder());
        var query = mock(SearchQuery.class, RETURNS_DEEP_STUBS);
        when(query.getUser().getId()).thenReturn(7L);
        when(query.getConversation().getId()).thenReturn(8L);
        when(query.getQueryText()).thenReturn("허용 문서 질문");
        when(queries.findById(9L)).thenReturn(Optional.of(query));
        List<SearchResult> rows = List.of(row(10L, "DENIED"), row(20L, "ALLOW20"), row(30L, "ALLOW30"),
            row(40L, "ALLOW40"), row(50L, "OVER_LIMIT50"), row(60L, "OVER_LIMIT60"));
        when(results.findByQuery_IdOrderByRankNo(9L)).thenReturn(rows);
        when(permissions.findReadableDocumentIds(eq(7L), any())).thenReturn(Set.of(20L, 30L, 40L, 50L, 60L));
        when(history.findRecentContext(7L, 8L, 9L, 3))
            .thenReturn(List.of(new ConversationContext("이전 질문", "허용된 이전 답변", "[99]")));

        var prepared = service.prepare(9L);

        assertThat(prepared.candidates()).extracting(candidate -> candidate.documentId()).containsExactly(20L, 30L, 40L);
        assertThat(prepared.searchResults()).containsExactlyElementsOf(rows.subList(1, 4));
        assertThat(prepared.sourceDocumentIdsJson()).isEqualTo("[20,30,40,99]");
        assertThat(prepared.promptText()).contains("ALLOW20", "ALLOW30", "ALLOW40", "허용된 이전 답변")
            .doesNotContain("DENIED", "OVER_LIMIT");
    }

    private SearchResult row(Long id, String text) {
        var row = mock(SearchResult.class, RETURNS_DEEP_STUBS);
        when(row.getChunk().getDocumentVersion().getDocument().getId()).thenReturn(id);
        when(row.getChunk().getDocumentVersion().getDocument().getTitle()).thenReturn(text);
        when(row.getChunk().getId()).thenReturn(id);
        when(row.getChunk().getChunkText()).thenReturn(text);
        when(row.getSimilarityScore()).thenReturn(BigDecimal.ONE);
        return row;
    }
}
