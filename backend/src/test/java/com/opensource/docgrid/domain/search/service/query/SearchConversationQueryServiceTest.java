package com.opensource.docgrid.domain.search.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.search.dto.ConversationCitationProjection;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.dto.ConversationQueryProjection;
import com.opensource.docgrid.domain.search.dto.ConversationRagResponseProjection;
import com.opensource.docgrid.domain.search.dto.ConversationSearchResultProjection;
import com.opensource.docgrid.domain.search.dto.response.SearchConversationResponse;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;

/**
 * 대화 상세 일괄 조립과 문맥 조회 순서, 벡터 검색용 후속 질문 확장 범위를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchConversationQueryService 단위 테스트")
class SearchConversationQueryServiceTest {

    @InjectMocks
    private SearchConversationQueryService searchConversationQueryService;

    @Mock private SearchConversationRepository searchConversationRepository;
    @Mock private SearchQueryRepository searchQueryRepository;
    @Mock private RagResponseRepository ragResponseRepository;
    @Mock private SearchResultRepository searchResultRepository;
    @Mock private ResponseCitationRepository responseCitationRepository;

    @Test
    @DisplayName("대화 상세는 질문별 단건 조회 없이 결과·RAG·citation을 각각 한 번에 조립한다")
    void getConversation_batchesTurnDetailsAndPreservesResponseContract() {
        SearchConversation conversation = mock(SearchConversation.class);
        given(conversation.getId()).willReturn(10L);
        given(conversation.getTitle()).willReturn("대화 제목");
        given(searchConversationRepository.findByIdAndUser_Id(10L, 20L)).willReturn(Optional.of(conversation));

        LocalDateTime olderTime = LocalDateTime.of(2026, 9, 12, 10, 0);
        LocalDateTime newerTime = olderTime.plusMinutes(1);
        given(searchQueryRepository.findConversationDetailQueries(
            org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(List.of(
            new ConversationQueryProjection(3L, "RAG 생성 전 질문", newerTime.plusMinutes(1)),
            new ConversationQueryProjection(2L, "새 질문", newerTime),
            new ConversationQueryProjection(1L, "이전 질문", olderTime)
        ));
        given(searchResultRepository.findConversationDetailResults(List.of(1L, 2L, 3L))).willReturn(List.of(
            new ConversationSearchResultProjection(
                1L, 7, 101L, 201L, "문서 A", "근거 A", 3, new BigDecimal("0.910000"),
                "3.2 개봉 후 환불",
                "{\"headingPath\":[\"3. 환불 정책\",\"3.2 개봉 후 환불\"],\"headingLevel\":2}"
            ),
            new ConversationSearchResultProjection(
                2L, 1, 102L, 202L, "문서 B", "근거 B", 4, new BigDecimal("0.900000"), null, null
            ),
            new ConversationSearchResultProjection(
                3L, 1, 103L, 203L, "문서 C", "근거 C", 5, new BigDecimal("0.890000"), null, null
            )
        ));
        given(ragResponseRepository.findConversationDetailResponses(List.of(1L, 2L, 3L))).willReturn(List.of(
            new ConversationRagResponseProjection(1L, ResultStatus.SUCCESS, "완료 답변"),
            new ConversationRagResponseProjection(2L, ResultStatus.PROCESSING, null)
        ));
        given(responseCitationRepository.findConversationDetailCitations(List.of(1L, 2L, 3L))).willReturn(List.of(
            new ConversationCitationProjection(1L, "[1]", 101L, "문서 A", 201L, 3, "근거 A", "3.2 개봉 후 환불", null),
            new ConversationCitationProjection(2L, "[1]", 102L, "문서 B", 202L, 4, "근거 B", null, null)
        ));

        SearchConversationResponse response = searchConversationQueryService.getConversation(10L, 20L);

        assertThat(response.turns()).extracting(SearchConversationResponse.Turn::queryId)
            .containsExactly(1L, 2L, 3L);
        assertThat(response.turns().get(0).response().ragStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(response.turns().get(0).response().answer()).isEqualTo("완료 답변");
        assertThat(response.turns().get(0).response().results()).hasSize(1);
        assertThat(response.turns().get(0).response().results().get(0).rank()).isEqualTo(1);
        assertThat(response.turns().get(0).response().citations()).hasSize(1);
        assertThat(response.turns().get(0).response().results().get(0).sectionPath())
            .isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불");
        assertThat(response.turns().get(0).response().citations().get(0).sectionPath())
            .isEqualTo("3.2 개봉 후 환불");
        assertThat(response.turns().get(1).response().results().get(0).sectionPath()).isNull();
        assertThat(response.turns().get(1).response().ragStatus()).isEqualTo(ResultStatus.PROCESSING);
        assertThat(response.turns().get(1).response().answer()).isNull();
        assertThat(response.turns().get(1).response().citations()).isEmpty();
        assertThat(response.turns().get(2).response().ragStatus()).isEqualTo(ResultStatus.PROCESSING);
        assertThat(response.turns().get(2).response().answer()).isNull();
        assertThat(response.turns().get(2).response().citations()).isEmpty();
        verify(searchResultRepository).findConversationDetailResults(List.of(1L, 2L, 3L));
        verify(ragResponseRepository).findConversationDetailResponses(List.of(1L, 2L, 3L));
        verify(responseCitationRepository).findConversationDetailCitations(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("질문이 없는 대화는 관련 테이블 일괄 조회를 생략한다")
    void getConversation_skipsBulkReadsWhenConversationHasNoQueries() {
        SearchConversation conversation = mock(SearchConversation.class);
        given(conversation.getId()).willReturn(10L);
        given(conversation.getTitle()).willReturn("빈 대화");
        given(searchConversationRepository.findByIdAndUser_Id(10L, 20L)).willReturn(Optional.of(conversation));
        given(searchQueryRepository.findConversationDetailQueries(
            org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(List.of());

        SearchConversationResponse response = searchConversationQueryService.getConversation(10L, 20L);

        assertThat(response.turns()).isEmpty();
        verifyNoInteractions(searchResultRepository, responseCitationRepository);
        verifyNoInteractions(ragResponseRepository);
    }

    @Test
    @DisplayName("저장소의 최신순 문맥을 시간순으로 뒤집어 반환한다")
    void findRecentContext_reversesNewestFirstRepositoryResult() {
        ConversationContext older = new ConversationContext("첫 질문", "첫 답변");
        ConversationContext newer = new ConversationContext("둘째 질문", "둘째 답변");
        given(ragResponseRepository.findRecentConversationContext(
            org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq(30L),
            org.mockito.ArgumentMatchers.any(Pageable.class)
        )).willReturn(List.of(newer, older));

        List<ConversationContext> result = searchConversationQueryService.findRecentContext(10L, 30L, 3);

        assertThat(result).containsExactly(older, newer);
    }

    @Test
    @DisplayName("벡터 검색 문장은 최근 질문 두 개와 현재 질문만 포함한다")
    void contextualizeRetrieval_usesOnlyTwoMostRecentQuestions() {
        List<ConversationContext> context = List.of(
            new ConversationContext("제외될 질문", "답변"),
            new ConversationContext("직전 두 번째 질문", "답변"),
            new ConversationContext("직전 질문", "답변")
        );

        String result = searchConversationQueryService.contextualizeRetrieval("그중 신청 방법은?", context);

        assertThat(result)
            .isEqualTo("이전 질문: 직전 두 번째 질문 / 직전 질문\n현재 질문: 그중 신청 방법은?")
            .doesNotContain("제외될 질문");
    }
}
