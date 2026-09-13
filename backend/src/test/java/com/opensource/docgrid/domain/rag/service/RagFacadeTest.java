package com.opensource.docgrid.domain.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.RagAnswer;
import com.opensource.docgrid.domain.rag.dto.RagEnqueueOutcome;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.service.command.RagResponseCommandService;
import com.opensource.docgrid.domain.rag.service.command.ResponseCitationCommandService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.repository.SearchResultRepository;
import com.opensource.docgrid.domain.search.service.query.SearchConversationQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.observability.RagJobCompletionMetricEvent;
import com.opensource.docgrid.global.observability.RagJobCompletionMetricEvent.Outcome;

import jakarta.persistence.EntityManager;

/**
 * #218(비동기 Job 큐 전환) 이후 RagFacade는 enqueue()(검색 직후 동기, 프롬프트 조립만)와
 * processJob()(RagJobWorker가 비동기로 호출, 실제 LLM 생성+영속화)로 나뉜다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RagFacade 단위 테스트")
class RagFacadeTest {

    @InjectMocks
    private RagFacade ragFacade;

    @Mock
    private PromptBuilder promptBuilder;

    @Mock
    private OllamaClient ollamaClient;

    @Mock
    private RagResponseCommandService ragResponseCommandService;

    @Mock
    private ResponseCitationCommandService responseCitationCommandService;

    @Mock
    private RagResponseRepository ragResponseRepository;

    @Mock
    private SearchResultRepository searchResultRepository;

    @Mock
    private SearchConversationQueryService searchConversationQueryService;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private static final Long QUERY_ID = 100L;
    private static final Long CONVERSATION_ID = 50L;
    private static final Long JOB_ID = 999L;

    // === enqueue() ===

    @Test
    @DisplayName("enqueue: NO_CONTEXT면 프롬프트 조립·PROCESSING 저장 없이 고정 응답으로 즉시 끝난다")
    void enqueue_noQualifiedCandidates_returnsDoneWithFixedAnswer() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);
        RagResponse noContextResponse = RagResponse.builder()
            .answerText("관련 문서를 찾지 못했습니다.")
            .status(ResultStatus.SUCCESS)
            .build();
        given(ragResponseCommandService.createNoContext(queryRef)).willReturn(noContextResponse);

        RagEnqueueOutcome outcome = ragFacade.enqueue(CONVERSATION_ID, QUERY_ID, "질문", List.of());

        assertThat(outcome.pending()).isFalse();
        assertThat(outcome.immediateAnswer().answerText()).isEqualTo("관련 문서를 찾지 못했습니다.");
        assertThat(outcome.immediateAnswer().citations()).isEmpty();
        then(promptBuilder).should(never()).build(anyString(), any(), any());
        then(ragResponseCommandService).should(times(1)).createNoContext(queryRef);
        then(ragResponseCommandService).should(never()).createPending(any(), anyString());
        then(applicationEventPublisher).should()
            .publishEvent(new RagJobCompletionMetricEvent(Outcome.NO_CONTEXT));
    }

    @Test
    @DisplayName("enqueue: 검색 후보가 있으면 프롬프트만 조립해 PROCESSING으로 저장하고 pending을 반환한다(LLM 호출 없음)")
    void enqueue_withCandidates_savesPendingWithoutCallingOllama() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);

        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 10L, 100L, "청크 내용", 12, "인사규정", new BigDecimal("0.9")
        );
        List<VectorSearchCandidate> candidates = List.of(candidate);
        given(searchConversationQueryService.findRecentContext(CONVERSATION_ID, QUERY_ID, 3))
            .willReturn(List.of());
        given(promptBuilder.build(eq("연차 규정 알려줘"), eq(candidates), eq(List.of())))
            .willReturn("조립된 프롬프트");
        given(ragResponseCommandService.createPending(queryRef, "조립된 프롬프트"))
            .willReturn(RagResponse.builder().status(ResultStatus.PROCESSING).build());

        RagEnqueueOutcome outcome = ragFacade.enqueue(
            CONVERSATION_ID, QUERY_ID, "연차 규정 알려줘", candidates
        );

        assertThat(outcome.pending()).isTrue();
        assertThat(outcome.immediateAnswer()).isNull();
        then(ollamaClient).should(never()).generate(anyString());
        then(ragResponseCommandService).should(times(1)).createPending(queryRef, "조립된 프롬프트");
    }

    @Test
    @DisplayName("enqueue: 검색 후보가 3개를 넘으면 LLM 프롬프트에는 상위 3개만 전달한다")
    void enqueue_moreThanMaxPromptCandidates_truncatesForPrompt() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);

        List<VectorSearchCandidate> candidates = List.of(
            new VectorSearchCandidate(1L, 10L, 100L, "청크1", 1, "문서1", new BigDecimal("0.9")),
            new VectorSearchCandidate(2L, 20L, 200L, "청크2", 2, "문서2", new BigDecimal("0.8")),
            new VectorSearchCandidate(3L, 30L, 300L, "청크3", 3, "문서3", new BigDecimal("0.7")),
            new VectorSearchCandidate(4L, 40L, 400L, "청크4", 4, "문서4", new BigDecimal("0.6")),
            new VectorSearchCandidate(5L, 50L, 500L, "청크5", 5, "문서5", new BigDecimal("0.5"))
        );
        List<VectorSearchCandidate> expectedPromptCandidates = candidates.subList(0, 3);
        given(searchConversationQueryService.findRecentContext(CONVERSATION_ID, QUERY_ID, 3))
            .willReturn(List.of());
        given(promptBuilder.build(anyString(), eq(expectedPromptCandidates), eq(List.of())))
            .willReturn("조립된 프롬프트");
        given(ragResponseCommandService.createPending(queryRef, "조립된 프롬프트"))
            .willReturn(RagResponse.builder().status(ResultStatus.PROCESSING).build());

        ragFacade.enqueue(CONVERSATION_ID, QUERY_ID, "질문", candidates);

        then(promptBuilder).should(times(1)).build(anyString(), eq(expectedPromptCandidates), eq(List.of()));
    }

    // === processJob() ===

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 20})
    @DisplayName("검색 후보 수와 무관하게 프롬프트와 citation은 동일한 상위 3건 이내의 후보를 사용한다")
    void processJob_usesSameTopCandidatesAsEnqueue(int candidateCount) {
        // 1. 검색 시점의 후보와 같은 순서로 저장된 검색 결과를 준비한다.
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        given(entityManager.getReference(SearchQuery.class, QUERY_ID)).willReturn(queryRef);
        List<VectorSearchCandidate> candidates = new ArrayList<>();
        List<SearchResult> searchResults = new ArrayList<>();
        for (int i = 1; i <= candidateCount; i++) {
            Long chunkId = i * 10L;
            Long documentId = i * 100L;
            BigDecimal score = BigDecimal.ONE.subtract(BigDecimal.valueOf(i, 2));
            candidates.add(new VectorSearchCandidate(
                null, chunkId, documentId, "청크" + i, i, "문서" + i, score
            ));
            SearchResult searchResult = deepStubSearchResult(
                documentId, chunkId, "청크" + i, i, "문서" + i, score
            );
            given(searchResult.getId()).willReturn(500L + i);
            searchResults.add(searchResult);
        }
        RagResponse job = RagResponse.builder()
            .query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(searchConversationQueryService.findRecentContext(CONVERSATION_ID, QUERY_ID, 3))
            .willReturn(List.of());
        given(promptBuilder.build(anyString(), any(), eq(List.of()))).willReturn("조립된 프롬프트");
        given(ragResponseCommandService.createPending(queryRef, "조립된 프롬프트")).willReturn(job);
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));
        given(ollamaClient.generate("조립된 프롬프트"))
            .willReturn(new OllamaGenerateResult("qwen2.5:7b", "정상 답변", 100, 20, 900));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(searchResults);
        given(ragResponseCommandService.completeSuccess(eq(job), any())).willReturn(true);

        // 2. 접수와 비동기 완료를 순차 실행해 두 단계의 후보 선택을 함께 검증한다.
        ragFacade.enqueue(CONVERSATION_ID, QUERY_ID, "질문", candidates);
        boolean completed = ragFacade.processJob(JOB_ID);

        // 3. 개수뿐 아니라 프롬프트 후보, 청크 ID, 검색 결과 ID의 순서까지 일치해야 한다.
        int expectedCount = Math.min(candidateCount, 3);
        List<VectorSearchCandidate> expectedCandidates = candidates.subList(0, expectedCount);
        ArgumentCaptor<List<VectorSearchCandidate>> citationCandidates = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<SearchResult>> citationResults = ArgumentCaptor.forClass(List.class);
        assertThat(completed).isTrue();
        then(promptBuilder).should(times(1)).build("질문", expectedCandidates, List.of());
        then(responseCitationCommandService).should(times(1))
            .saveAll(eq(job), citationCandidates.capture(), citationResults.capture());
        assertThat(citationCandidates.getValue()).containsExactlyElementsOf(expectedCandidates);
        assertThat(citationCandidates.getValue()).extracting(VectorSearchCandidate::chunkId)
            .containsExactlyElementsOf(List.of(10L, 20L, 30L).subList(0, expectedCount));
        assertThat(citationResults.getValue()).extracting(SearchResult::getId)
            .containsExactlyElementsOf(List.of(501L, 502L, 503L).subList(0, expectedCount));
        then(searchResultRepository).should(times(1)).findByQuery_IdOrderByRankNo(QUERY_ID);
    }

    @Test
    @DisplayName("processJob 정상 흐름: Ollama 호출 성공 시 completeSuccess와 citation을 저장한다")
    void processJob_success_savesResponseAndCitations() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        OllamaGenerateResult ollamaResult = new OllamaGenerateResult("qwen2.5:7b", "연차는 15일입니다.", 100, 20, 900);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);

        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));
        given(ragResponseCommandService.completeSuccess(eq(job), any())).willReturn(true);

        boolean completed = ragFacade.processJob(JOB_ID);

        assertThat(completed).isTrue();
        then(ragResponseCommandService).should(times(1)).completeSuccess(eq(job), any());
        then(responseCitationCommandService).should(times(1)).saveAll(eq(job), any(), eq(List.of(searchResult)));
        then(ragResponseCommandService).should(never()).completeFailed(any(), anyString(), anyString());
        then(applicationEventPublisher).should()
            .publishEvent(new RagJobCompletionMetricEvent(Outcome.SUCCESS));
    }

    @Test
    @DisplayName("processJob 경합(#288): findById 시점에 이미 PROCESSING이 아니면 Ollama 호출 없이 즉시 false를 반환한다")
    void processJob_alreadyFinalizedBeforeFetch_skipsOllamaCallAndReturnsFalse() {
        SearchQuery queryRef = mock(SearchQuery.class);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.FAILED).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        boolean completed = ragFacade.processJob(JOB_ID);

        assertThat(completed).isFalse();
        then(ollamaClient).should(never()).generate(anyString());
        then(ragResponseCommandService).should(never()).completeSuccess(any(), any());
        then(ragResponseCommandService).should(never()).completeFailed(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("processJob 경합(#288): completeSuccess가 false를 반환하면(스위퍼가 이미 확정함) citation을 저장하지 않고 false를 반환한다")
    void processJob_completeSuccessLosesRace_skipsCitationsAndReturnsFalse() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        OllamaGenerateResult ollamaResult = new OllamaGenerateResult("qwen2.5:7b", "연차는 15일입니다.", 100, 20, 900);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);
        given(ragResponseCommandService.completeSuccess(eq(job), any())).willReturn(false);

        boolean completed = ragFacade.processJob(JOB_ID);

        assertThat(completed).isFalse();
        then(responseCitationCommandService).should(never()).saveAll(any(), any(), any());
        then(searchResultRepository).should(never()).findByQuery_IdOrderByRankNo(any());
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("processJob Ollama 실패: completeFailed로 최상위 후보 원문을 인용한 extractive fallback을 저장한다")
    void processJob_ollamaFails_savesFailedWithExtractiveFallback() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        given(ollamaClient.generate("조립된 프롬프트"))
            .willThrow(new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE));

        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));
        given(ragResponseCommandService.completeFailed(eq(job), anyString(), anyString())).willReturn(true);

        boolean completed = ragFacade.processJob(JOB_ID);

        assertThat(completed).isTrue();
        then(ragResponseCommandService).should(times(1)).completeFailed(
            eq(job), argThatFallbackContains("AI 답변 생성이 지연", "청크 내용", "인사규정"), anyString()
        );
        then(responseCitationCommandService).should(never()).saveAll(any(), any(), any());
        then(applicationEventPublisher).should()
            .publishEvent(new RagJobCompletionMetricEvent(Outcome.PROVIDER_FALLBACK));
    }

    @Test
    @DisplayName("processJob 경합(#288): Ollama 실패 시에도 completeFailed가 false면(스위퍼가 이미 확정함) processJob도 false를 반환한다")
    void processJob_completeFailedLosesRace_returnsFalse() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));
        given(ollamaClient.generate("조립된 프롬프트"))
            .willThrow(new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE));
        given(ragResponseCommandService.completeFailed(eq(job), anyString(), anyString())).willReturn(false);

        boolean completed = ragFacade.processJob(JOB_ID);

        assertThat(completed).isFalse();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("processJob LLM 무관 판단: 답변이 안내 문구로 시작하면 citation을 저장하지 않는다")
    void processJob_llmJudgesIrrelevant_skipsCitations() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        OllamaGenerateResult ollamaResult =
            new OllamaGenerateResult("qwen2.5:7b", "관련 문서를 찾지 못했습니다.", 100, 10, 500);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);
        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));
        given(ragResponseCommandService.completeSuccess(eq(job), any())).willReturn(true);

        ragFacade.processJob(JOB_ID);

        then(ragResponseCommandService).should(times(1)).completeSuccess(eq(job), any());
        then(responseCitationCommandService).should(never()).saveAll(any(), any(), any());
        then(searchResultRepository).should(never()).findByQuery_IdOrderByRankNo(any());
    }

    @Test
    @DisplayName("processJob 무관 문구 혼입: 정상 답변 중간에 안내 문구가 섞이면 그 지점부터 제거한 뒤 저장하고 citation은 유지한다")
    void processJob_phraseEmbeddedInAnswer_trimsBeforePersistingAndKeepsCitations() {
        SearchQuery queryRef = mock(SearchQuery.class);
        given(queryRef.getId()).willReturn(QUERY_ID);
        RagResponse job = RagResponse.builder().query(queryRef).promptText("조립된 프롬프트").status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));

        String answerWithEcho = "pwd는 현재 디렉토리를 출력합니다. "
            + "관련 문서를 찾지 못했습니다. 질문 주제와 관련된 문서가 없습니다.";
        OllamaGenerateResult ollamaResult = new OllamaGenerateResult("qwen2.5:7b", answerWithEcho, 100, 50, 500);
        given(ollamaClient.generate("조립된 프롬프트")).willReturn(ollamaResult);
        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "디렉토리 명령어", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));
        given(ragResponseCommandService.completeSuccess(eq(job), any())).willReturn(true);

        ragFacade.processJob(JOB_ID);

        then(ragResponseCommandService).should(times(1)).completeSuccess(eq(job),
            org.mockito.ArgumentMatchers.<OllamaGenerateResult>argThat(result ->
                result.answerText().equals("pwd는 현재 디렉토리를 출력합니다.")
            ));
        then(responseCitationCommandService).should(times(1)).saveAll(eq(job), any(), eq(List.of(searchResult)));
    }

    // === markUnexpectedFailure() ===

    @Test
    @DisplayName("markUnexpectedFailure: PROCESSING job을 실제 종료하면 예상 밖 실패 메트릭을 발행한다")
    void markUnexpectedFailure_completed_publishesMetricEvent() {
        RagResponse job = RagResponse.builder().status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));
        given(ragResponseCommandService.completeFailed(job, "답변 생성 중 예상치 못한 오류가 발생했습니다.", "bug"))
            .willReturn(true);

        boolean completed = ragFacade.markUnexpectedFailure(JOB_ID, "bug");

        assertThat(completed).isTrue();
        then(applicationEventPublisher).should()
            .publishEvent(new RagJobCompletionMetricEvent(Outcome.UNEXPECTED_FAILURE));
    }

    @Test
    @DisplayName("markUnexpectedFailure: 조건부 종료 경합에서 지면 메트릭을 발행하지 않는다")
    void markUnexpectedFailure_losesRace_doesNotPublishMetricEvent() {
        RagResponse job = RagResponse.builder().status(ResultStatus.PROCESSING).build();
        given(ragResponseRepository.findById(JOB_ID)).willReturn(Optional.of(job));
        given(ragResponseCommandService.completeFailed(job, "답변 생성 중 예상치 못한 오류가 발생했습니다.", "bug"))
            .willReturn(false);

        boolean completed = ragFacade.markUnexpectedFailure(JOB_ID, "bug");

        assertThat(completed).isFalse();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    // === failIfStillProcessing() ===

    @Test
    @DisplayName("failIfStillProcessing: 검색 후보가 있으면 extractive fallback으로 강제 종료하고 true를 반환한다")
    void failIfStillProcessing_withCandidates_forceFailsWithFallbackAndReturnsTrue() {
        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));
        given(ragResponseRepository.forceFailIfProcessing(eq(JOB_ID), anyString(), anyString())).willReturn(1);

        boolean result = ragFacade.failIfStillProcessing(JOB_ID, QUERY_ID);

        assertThat(result).isTrue();
        then(ragResponseRepository).should(times(1)).forceFailIfProcessing(
            eq(JOB_ID), argThatFallbackContains("AI 답변 생성이 지연", "청크 내용", "인사규정"), anyString()
        );
        then(applicationEventPublisher).should()
            .publishEvent(new RagJobCompletionMetricEvent(Outcome.TIMEOUT_SWEPT));
    }

    @Test
    @DisplayName("failIfStillProcessing: 이미 다른 트랜잭션에서 끝난 job이면(영향받은 행 0건) false를 반환한다")
    void failIfStillProcessing_alreadyFinishedByWorker_returnsFalse() {
        SearchResult searchResult = deepStubSearchResult(100L, 10L, "청크 내용", 12, "인사규정", new BigDecimal("0.9"));
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of(searchResult));
        given(ragResponseRepository.forceFailIfProcessing(eq(JOB_ID), anyString(), anyString())).willReturn(0);

        boolean result = ragFacade.failIfStillProcessing(JOB_ID, QUERY_ID);

        assertThat(result).isFalse();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("failIfStillProcessing: 검색 후보가 없으면(이론상 도달 불가능한 방어 분기) 고정 안내 문구로 강제 종료한다")
    void failIfStillProcessing_noCandidates_usesUnexpectedFailureAnswerText() {
        given(searchResultRepository.findByQuery_IdOrderByRankNo(QUERY_ID)).willReturn(List.of());
        given(ragResponseRepository.forceFailIfProcessing(eq(JOB_ID), anyString(), anyString())).willReturn(1);

        ragFacade.failIfStillProcessing(JOB_ID, QUERY_ID);

        then(ragResponseRepository).should(times(1))
            .forceFailIfProcessing(eq(JOB_ID), eq("답변 생성 중 예상치 못한 오류가 발생했습니다."), anyString());
    }

    private SearchResult deepStubSearchResult(
        Long documentId, Long chunkId, String chunkText, Integer pageNo, String documentTitle, BigDecimal similarityScore
    ) {
        SearchResult searchResult = mock(SearchResult.class, RETURNS_DEEP_STUBS);
        given(searchResult.getSimilarityScore()).willReturn(similarityScore);
        given(searchResult.getEmbedding()).willReturn(null);
        given(searchResult.getChunk().getId()).willReturn(chunkId);
        given(searchResult.getChunk().getChunkText()).willReturn(chunkText);
        given(searchResult.getChunk().getPageNo()).willReturn(pageNo);
        given(searchResult.getChunk().getDocumentVersion().getDocument().getId()).willReturn(documentId);
        given(searchResult.getChunk().getDocumentVersion().getDocument().getTitle()).willReturn(documentTitle);
        return searchResult;
    }

    private String argThatFallbackContains(String... fragments) {
        // Mockito의 argThat과 조합해 여러 부분 문자열을 한 번에 검증하기 위한 헬퍼.
        return org.mockito.ArgumentMatchers.<String>argThat(text -> {
            for (String fragment : fragments) {
                if (!text.contains(fragment)) return false;
            }
            return true;
        });
    }
}
