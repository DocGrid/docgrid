package com.opensource.docgrid.domain.rag.service.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.fixture.SearchQueryFixture;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagResponseCommandService 단위 테스트")
class RagResponseCommandServiceTest {

    @InjectMocks
    private RagResponseCommandService ragResponseCommandService;

    @Mock
    private RagResponseRepository ragResponseRepository;

    @Test
    @DisplayName("createPending: PROCESSING 상태로 프롬프트만 먼저 저장한다(답변 없음)")
    void createPending_savesWithProcessingStatus() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        given(ragResponseRepository.save(any(RagResponse.class))).willAnswer(i -> i.getArgument(0));

        ragResponseCommandService.createPending(query, "조립된 프롬프트");

        ArgumentCaptor<RagResponse> captor = ArgumentCaptor.forClass(RagResponse.class);
        then(ragResponseRepository).should(times(1)).save(captor.capture());

        RagResponse saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.PROCESSING);
        assertThat(saved.getAnswerText()).isNull();
        assertThat(saved.getLlmProvider()).isEqualTo("Ollama");
        assertThat(saved.getPromptText()).isEqualTo("조립된 프롬프트");
    }

    @Test
    @DisplayName("completeSuccessOwned: 현재 token과 유효한 lease로 확정하면 true를 반환한다")
    void completeSuccess_requestsConditionalUpdateAndReturnsTrueWhenApplied() {
        RagResponse pending = RagResponse.builder().status(ResultStatus.PROCESSING).promptText("조립된 프롬프트").build();
        OllamaGenerateResult result = new OllamaGenerateResult(
            "qwen2.5:7b", "연차는 입사 1년 기준 15일 부여됩니다.", 120, 45, 1800
        );
        given(ragResponseRepository.completeSuccessIfOwned(
            eq(pending.getId()), eq("token-a"), any(LocalDateTime.class),
            eq("연차는 입사 1년 기준 15일 부여됩니다."), eq("qwen2.5:7b"), eq(120), eq(45), eq(1800)
        )).willReturn(1);

        boolean completed = ragResponseCommandService.completeSuccessOwned(pending, "token-a", result);

        assertThat(completed).isTrue();
    }

    @Test
    @DisplayName("completeSuccess: 조건부 UPDATE가 영향받은 행 0건이면(#288, 스위퍼가 이미 확정함) false를 반환한다")
    void completeSuccess_conditionalUpdateAppliesToNoRows_returnsFalse() {
        RagResponse pending = RagResponse.builder().status(ResultStatus.PROCESSING).promptText("조립된 프롬프트").build();
        OllamaGenerateResult result = new OllamaGenerateResult("qwen2.5:7b", "답변", 10, 5, 100);
        given(ragResponseRepository.completeSuccessIfOwned(any(), any(), any(), any(), any(), any(), any(), any()))
            .willReturn(0);

        boolean completed = ragResponseCommandService.completeSuccessOwned(pending, "token-a", result);

        assertThat(completed).isFalse();
    }

    @Test
    @DisplayName("completeFailedOwned: 현재 token의 실패 확정만 true를 반환한다")
    void completeFailed_requestsConditionalUpdateAndReturnsTrueWhenApplied() {
        RagResponse pending = RagResponse.builder().status(ResultStatus.PROCESSING).promptText("조립된 프롬프트").build();
        given(ragResponseRepository.completeFailureIfOwned(
            eq(pending.getId()), eq("token-a"), any(LocalDateTime.class),
            eq("extractive fallback 텍스트"), eq("Ollama 서버 연결 실패")
        )).willReturn(1);

        boolean completed = ragResponseCommandService.completeFailedOwned(
            pending, "token-a", "extractive fallback 텍스트", "Ollama 서버 연결 실패");

        assertThat(completed).isTrue();
    }

    @Test
    @DisplayName("completeFailed: 조건부 UPDATE가 영향받은 행 0건이면(#288, 스위퍼가 이미 확정함) false를 반환한다")
    void completeFailed_conditionalUpdateAppliesToNoRows_returnsFalse() {
        RagResponse pending = RagResponse.builder().status(ResultStatus.PROCESSING).promptText("조립된 프롬프트").build();
        given(ragResponseRepository.completeFailureIfOwned(any(), any(), any(), any(), any())).willReturn(0);

        boolean completed = ragResponseCommandService.completeFailedOwned(pending, "token-a", "fallback", "error");

        assertThat(completed).isFalse();
    }

    @Test
    @DisplayName("createNoContext: SUCCESS 상태로 고정 안내 문구를 저장한다")
    void createNoContext_savesWithFixedAnswer() {
        SearchQuery query = SearchQueryFixture.createProcessing();
        given(ragResponseRepository.save(any(RagResponse.class))).willAnswer(i -> i.getArgument(0));

        ragResponseCommandService.createNoContext(query);

        ArgumentCaptor<RagResponse> captor = ArgumentCaptor.forClass(RagResponse.class);
        then(ragResponseRepository).should(times(1)).save(captor.capture());

        RagResponse saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(saved.getAnswerText()).isEqualTo("관련 문서를 찾지 못했습니다.");
        assertThat(saved.getLlmProvider()).isNull();
        assertThat(saved.getLlmModelName()).isNull();
        assertThat(saved.getPromptText()).isNull();
        assertThat(saved.getInputTokenCount()).isNull();
        assertThat(saved.getOutputTokenCount()).isNull();
        assertThat(saved.getLatencyMs()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }
}
