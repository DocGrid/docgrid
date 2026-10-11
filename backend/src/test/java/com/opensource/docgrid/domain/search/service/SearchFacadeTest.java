package com.opensource.docgrid.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.opensource.docgrid.domain.embedding.dto.EmbedResult;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.service.query.QueryEmbeddingService;
import com.opensource.docgrid.domain.permission.service.query.PermissionQueryService;
import com.opensource.docgrid.domain.search.config.HybridSearchProperties;
import com.opensource.docgrid.domain.search.dto.SearchAdmission;
import com.opensource.docgrid.domain.search.dto.SearchOutcome;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.dto.request.SearchRequest;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.service.command.SearchQueryCommandService;
import com.opensource.docgrid.domain.search.service.command.SearchResultCommandService;
import com.opensource.docgrid.domain.search.service.query.AccessibleDocumentQueryService;
import com.opensource.docgrid.domain.search.service.query.HybridSearchQueryService;
import com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SearchFacade 단위 테스트")
class SearchFacadeTest {

    @InjectMocks private SearchFacade searchFacade;

    @Mock private QueryEmbeddingService queryEmbeddingService;
    @Mock private SearchConversationQueryService searchConversationQueryService;
    @Mock private SearchQueryCommandService searchQueryCommandService;
    @Mock private AccessibleDocumentQueryService accessibleDocumentQueryService;
    @Mock private VectorSearchQueryService vectorSearchQueryService;
    @Mock private HybridSearchQueryService hybridSearchQueryService;
    @Mock private HybridSearchProperties hybridSearchProperties;
    @Mock private PermissionQueryService permissionQueryService;
    @Mock private SearchResultCommandService searchResultCommandService;

    private static final Long USER_ID = 1L;
    private static final Long QUERY_ID = 10L;
    private static final SearchRequest REQUEST = new SearchRequest("검색어", 5, null);

    @BeforeEach
    void setUpConversation() {
        given(searchQueryCommandService.createProcessing(USER_ID, null, null, "검색어", 5))
            .willReturn(new SearchAdmission(50L, QUERY_ID));
        given(searchConversationQueryService.findRecentContext(50L, QUERY_ID, 2)).willReturn(List.of());
        given(searchConversationQueryService.contextualizeRetrieval(anyString(), any()))
            .willAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("정상 흐름: 후보 조회 후 live check를 거쳐 결과를 반환한다")
    void search_normalFlow_returnsResults() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        VectorSearchCandidate candidate = new VectorSearchCandidate(1L, 2L, 3L, "텍스트", 1, "제목", new BigDecimal("0.9"));

        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(vectorSearchQueryService.search(any(), any(), any(), anyInt())).willReturn(List.of(candidate));
        given(permissionQueryService.canReadDocument(USER_ID, 3L)).willReturn(true);
        SearchResult savedResult = mock(SearchResult.class);
        given(searchResultCommandService.saveAllAndComplete(anyLong(), any(), any(), any(), anyInt()))
            .willReturn(List.of(savedResult));

        SearchOutcome outcome = searchFacade.search(USER_ID, REQUEST);

        assertThat(outcome.response().results()).hasSize(1);
        assertThat(outcome.response().results().get(0).rank()).isEqualTo(1);
        assertThat(outcome.response().results().get(0).chunkId()).isEqualTo(2L);
        assertThat(outcome.candidates()).hasSize(1);
        assertThat(outcome.savedResults()).containsExactly(savedResult);
        then(searchResultCommandService).should(times(1))
            .saveAllAndComplete(anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("하이브리드가 꺼져 있으면 기존 벡터 단독 검색을 쓰고 하이브리드 서비스는 호출하지 않는다")
    void search_hybridDisabled_usesVectorSearchOnly() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        VectorSearchCandidate candidate = new VectorSearchCandidate(1L, 2L, 3L, "텍스트", 1, "제목", new BigDecimal("0.9"));
        given(hybridSearchProperties.isEnabled()).willReturn(false);
        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(vectorSearchQueryService.search(any(), any(), any(), anyInt())).willReturn(List.of(candidate));
        given(permissionQueryService.canReadDocument(USER_ID, 3L)).willReturn(true);
        given(searchResultCommandService.saveAllAndComplete(anyLong(), any(), any(), any(), anyInt()))
            .willReturn(List.of(mock(SearchResult.class)));

        searchFacade.search(USER_ID, REQUEST);

        then(vectorSearchQueryService).should(times(1)).search(any(), any(), any(), anyInt());
        then(hybridSearchQueryService).should(never()).search(any(), anyString(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("하이브리드가 켜져 있으면 문맥이 섞이지 않은 원문 질문으로 하이브리드 검색을 호출한다")
    void search_hybridEnabled_usesHybridSearchWithRawQuestion() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        VectorSearchCandidate candidate = new VectorSearchCandidate(1L, 2L, 3L, "텍스트", 1, "제목", new BigDecimal("0.9"));
        given(hybridSearchProperties.isEnabled()).willReturn(true);
        given(searchConversationQueryService.contextualizeRetrieval(anyString(), any()))
            .willReturn("이전 질문 + 검색어");
        given(queryEmbeddingService.embed("이전 질문 + 검색어")).willReturn(embedResult);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(hybridSearchQueryService.search(any(), anyString(), any(), any(), anyInt()))
            .willReturn(List.of(candidate));
        given(permissionQueryService.canReadDocument(USER_ID, 3L)).willReturn(true);
        given(searchResultCommandService.saveAllAndComplete(anyLong(), any(), any(), any(), anyInt()))
            .willReturn(List.of(mock(SearchResult.class)));

        SearchOutcome outcome = searchFacade.search(USER_ID, REQUEST);

        assertThat(outcome.response().results()).hasSize(1);
        // 벡터 임베딩에는 대화 문맥이 섞이지만 키워드 갈래에는 사용자가 입력한 원문만 전달한다.
        then(hybridSearchQueryService).should(times(1))
            .search(any(), org.mockito.ArgumentMatchers.eq("검색어"), any(), any(), anyInt());
        then(vectorSearchQueryService).should(never()).search(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("하이브리드 후보도 live check에서 읽기 권한이 없으면 결과에서 제외한다")
    void search_hybridEnabled_liveCheckStillFiltersCandidates() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        VectorSearchCandidate candidate = new VectorSearchCandidate(1L, 2L, 3L, "텍스트", 1, "제목", new BigDecimal("0.9"));
        given(hybridSearchProperties.isEnabled()).willReturn(true);
        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(hybridSearchQueryService.search(any(), anyString(), any(), any(), anyInt()))
            .willReturn(List.of(candidate));
        given(permissionQueryService.canReadDocument(USER_ID, 3L)).willReturn(false);
        given(searchResultCommandService.saveAllAndComplete(anyLong(), any(), any(), any(), anyInt()))
            .willReturn(List.of());

        SearchOutcome outcome = searchFacade.search(USER_ID, REQUEST);

        assertThat(outcome.response().results()).isEmpty();
        assertThat(outcome.candidates()).isEmpty();
    }

    @Test
    @DisplayName("접근 가능한 문서가 없으면 벡터 검색을 건너뛰고 빈 결과를 반환한다")
    void search_noPermittedIds_skipsVectorSearch() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);

        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of());

        SearchOutcome outcome = searchFacade.search(USER_ID, REQUEST);

        assertThat(outcome.response().results()).isEmpty();
        assertThat(outcome.candidates()).isEmpty();
        then(vectorSearchQueryService).should(never()).search(any(), anyLong(), any(), anyInt());
        then(searchResultCommandService).should(times(1))
            .saveAllAndComplete(anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("유사도 필터 결과가 비어있으면 검색 결과를 저장하지 않고 빈 후보를 반환한다")
    void search_noQualifiedCandidates_returnsEmptyOutcome() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);

        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(vectorSearchQueryService.search(any(), any(), any(), anyInt())).willReturn(List.of());
        given(searchResultCommandService.saveAllAndComplete(anyLong(), any(), any(), any(), anyInt()))
            .willReturn(List.of());

        SearchOutcome outcome = searchFacade.search(USER_ID, REQUEST);

        assertThat(outcome.response().results()).isEmpty();
        assertThat(outcome.candidates()).isEmpty();
        assertThat(outcome.savedResults()).isEmpty();
        then(permissionQueryService).should(never()).canReadDocument(anyLong(), anyLong());
        then(searchResultCommandService).should(times(1))
            .saveAllAndComplete(anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("live check에서 탈락한 후보는 결과에서 제외된다")
    void search_liveCheckFiltersOut_excludesCandidate() {
        EmbedResult embedResult = new EmbedResult(EmbeddingModelFixture.createDefaultModel(), new float[1024]);
        VectorSearchCandidate candidate = new VectorSearchCandidate(1L, 2L, 3L, "텍스트", 1, "제목", new BigDecimal("0.9"));

        given(queryEmbeddingService.embed(anyString())).willReturn(embedResult);
        given(accessibleDocumentQueryService.findReadableDocumentIds(USER_ID, null)).willReturn(List.of(3L));
        given(vectorSearchQueryService.search(any(), any(), any(), anyInt())).willReturn(List.of(candidate));
        given(permissionQueryService.canReadDocument(USER_ID, 3L)).willReturn(false);
        given(searchResultCommandService.saveAllAndComplete(anyLong(), any(), any(), any(), anyInt()))
            .willReturn(List.of());

        SearchOutcome outcome = searchFacade.search(USER_ID, REQUEST);

        assertThat(outcome.response().results()).isEmpty();
        assertThat(outcome.candidates()).isEmpty();
        then(searchResultCommandService).should(times(1))
            .saveAllAndComplete(anyLong(), any(), any(), any(), anyInt());
    }
}
