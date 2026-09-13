package com.opensource.docgrid.domain.rag.service;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.dto.RagAnswer;
import com.opensource.docgrid.domain.rag.dto.RagEnqueueOutcome;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.service.command.RagResponseCommandService;
import com.opensource.docgrid.domain.rag.service.command.ResponseCitationCommandService;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG 답변 생성 전체 흐름을 조율하는 Facade (F-RAG-05).
 *
 * <p>비동기 Job 큐 전환(#218) 이후 두 단계로 나뉜다:
 * <pre>
 * 1. enqueue()  — SearchController가 검색 직후 동기 호출. 프롬프트만 조립해 PROCESSING으로 저장하고
 *                 즉시 반환한다(LLM 호출 없음). candidates가 비어있으면(NO_CONTEXT) 여기서 바로 끝난다.
 * 2. processJob() — RagJobWorker가 PROCESSING row를 하나씩 꺼내 호출. 실제 OllamaClient 호출과
 *                    결과 영속화(rag_responses, response_citations)를 담당한다.
 * </pre>
 *
 * <p>SearchFacade와 별도 트랜잭션으로 분리되어 있다(SearchController가 순차 호출) — 검색 DB 작업이
 * enqueue()의 짧은 DB 작업과 하나의 커넥션을 오래 물고 있지 않도록 하기 위함이다.
 */
@Transactional
@Service
@RequiredArgsConstructor
@Slf4j
public class RagFacade {

    /*
     * LLM_FALLBACK_PREFIX          — Ollama 호출 실패 시 최상위 검색 후보 원문을 인용하며 붙이는
     *                                안내 문구(buildExtractiveFallbackAnswer 참고).
     * UNEXPECTED_FAILURE_ANSWER_TEXT — processJob() 내부에서 예상 못한 예외(버그 등)로 실패했을
     *                                때 쓰는 최소 안내 문구. extractive fallback과 달리 candidates를
     *                                다시 불러오지 않는다 — 이미 한 번 예상 밖으로 실패한 상황에서
     *                                추가 조회를 시도하다 또 실패할 위험을 만들지 않기 위함이다
     *                                (RagJobWorker 참고).
     * FALLBACK_EXCERPT_MAX_CODE_POINTS(300) — fallback 문구에 원문을 통째로 붙이면 답변이
     *                                지나치게 길어져, 미리보기 수준으로만 잘라 보여준다.
     * MAX_PROMPT_CANDIDATES(3)    — topK는 호출자가 1~20까지 자유롭게 요청할 수 있어
     *                                (SearchRequest), 후보 수를 그대로 프롬프트에 다 넣으면
     *                                prefill 시간이 예측 불가능해진다(#210). 검색 결과 수(topK)와
     *                                별개로 프롬프트와 정상 답변 citation 모두 상위 후보를 이 값까지
     *                                제한해, LLM에 제공하지 않은 후보가 출처로 추가되지 않게 한다.
     * NO_RELEVANT_DOC_PHRASE       — PromptBuilder가 LLM에게 무관한 문서일 때 이 문구로만 답하도록
     *                                지시한다(#65 INSTRUCTION 참고). 검색은 됐지만(candidates 존재)
     *                                LLM이 무관하다고 판단한 경우, 화면에 근거 문서를 같이 보여주면
     *                                안내 문구와 모순돼 보인다.
     * TIMEOUT_ERROR_MESSAGE        — RagJobTimeoutSweeper가 너무 오래 PROCESSING으로 남은 job을
     *                                강제 종료할 때 error_message에 남기는 문구(#286). Ollama
     *                                예외 메시지와 구분해, 나중에 로그/DB로 "진짜 실패"와 "큐
     *                                적체로 인한 강제 종료"를 구분할 수 있게 한다.
     */
    private static final String LLM_FALLBACK_PREFIX = "AI 답변 생성이 지연되고 있습니다. "
        + "가장 관련도 높은 문서에서 다음 내용을 찾았습니다:\n\n";
    private static final String UNEXPECTED_FAILURE_ANSWER_TEXT = "답변 생성 중 예상치 못한 오류가 발생했습니다.";
    private static final int FALLBACK_EXCERPT_MAX_CODE_POINTS = 300;
    private static final int MAX_PROMPT_CANDIDATES = 3;
    private static final int MAX_CONVERSATION_CONTEXT_TURNS = 3;
    private static final String NO_RELEVANT_DOC_PHRASE = "관련 문서를 찾지 못했습니다";
    private static final String TIMEOUT_ERROR_MESSAGE =
        "PROCESSING 상태 유지 시간이 임계값을 초과해 강제 종료됨(RagJobTimeoutSweeper)";

    private final PromptBuilder promptBuilder;
    private final OllamaClient ollamaClient;
    private final RagResponseCommandService ragResponseCommandService;
    private final ResponseCitationCommandService responseCitationCommandService;
    private final RagResponseRepository ragResponseRepository;
    private final SearchResultRepository searchResultRepository;
    private final SearchConversationQueryService searchConversationQueryService;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 검색 직후 SearchController가 동기 호출하는 접수 단계. Ollama는 아직 호출하지 않는다.
     *
     * <p>candidates가 비어있으면(NO_CONTEXT) LLM 호출 없이 고정 응답으로 즉시 끝낸다 — Job
     * 큐에 올릴 이유가 없다. 후보가 있으면 프롬프트만 조립하고(순수 문자열 처리라 빠름)
     * PROCESSING 상태로 저장한 뒤 즉시 반환한다 — 실제 LLM 호출은 나중에 RagJobWorker가
     * {@link #processJob}으로 한다.
     */
    public RagEnqueueOutcome enqueue(
        Long conversationId,
        Long queryId,
        String queryText,
        List<VectorSearchCandidate> candidates
    ) {
        SearchQuery queryRef = entityManager.getReference(SearchQuery.class, queryId);

        if (candidates.isEmpty()) {
            RagResponse ragResponse = ragResponseCommandService.createNoContext(queryRef);
            // LLM 호출을 생략한 정상 완료도 저장 Transaction이 커밋된 뒤 별도 결과로 집계한다.
            applicationEventPublisher.publishEvent(new RagJobCompletionMetricEvent(Outcome.NO_CONTEXT));
            log.info("[RAG] no context queryId={} responseId={}", queryId, ragResponse.getId());
            return RagEnqueueOutcome.done(RagAnswer.noContext(ragResponse.getAnswerText()));
        }

        List<VectorSearchCandidate> promptCandidates = candidates.size() > MAX_PROMPT_CANDIDATES
            ? candidates.subList(0, MAX_PROMPT_CANDIDATES)
            : candidates;
        List<ConversationContext> conversationContext = searchConversationQueryService.findRecentContext(
            conversationId, queryId, MAX_CONVERSATION_CONTEXT_TURNS
        );
        String prompt = promptBuilder.build(queryText, promptCandidates, conversationContext);
        RagResponse ragResponse = ragResponseCommandService.createPending(queryRef, prompt);
        log.info("[RAG] enqueued queryId={} responseId={}", queryId, ragResponse.getId());
        return RagEnqueueOutcome.stillPending();
    }

    /**
     * RagJobWorker가 PROCESSING job을 하나 집어 실제로 처리하는 단계 — 여기서 처음으로
     * Ollama를 호출한다. 성공하면 SUCCESS로 확정하고 citation을 저장하고, 실패하면
     * extractive fallback을 채운 채 FAILED로 확정한다.
     *
     * <p>{@code job} 객체가 아니라 {@code jobId}만 받아 이 메서드 자신의 트랜잭션 안에서 다시
     * 조회하는 이유: RagJobWorker가 {@code findFirstByStatusOrderByCreatedAtAsc()}로 꺼낸
     * job은 그 조회 시점에 트랜잭션이 끝나 detached 상태다. 원래(#218) 이 detached 인스턴스를
     * 그대로 받아 필드만 바꾸면 dirty checking이 감지 못해 DB에 반영되지 않는 버그가 있었는데,
     * 지금은 완료 처리 자체가 dirty checking에 의존하지 않는다({@link
     * RagResponseCommandService#completeSuccess}/{@link RagResponseCommandService#completeFailed}
     * 참고, #288) — 그래도 {@code findById(jobId)}로 다시 조회해 이 트랜잭션 시점 기준
     * 최신 상태(예: {@code promptText})를 읽는다.
     *
     * <p>{@code completeSuccess}/{@code completeFailed}는 RagJobTimeoutSweeper가 이미 이
     * job을 먼저 확정해버렸으면 {@code false}를 반환한다(#288) — 이 경우 citation 저장을
     * 건너뛰고 {@code false}를 그대로 반환해, 호출자(RagJobWorker)가 중복 알림을 보내지
     * 않게 한다.
     *
     * <p>{@code findById} 직후 status가 이미 PROCESSING이 아니면 곧바로 {@code false}를
     * 반환하고 Ollama를 아예 호출하지 않는다 — RagJobWorker가 이 job을 집어든 뒤, 여기서
     * {@code findById}로 다시 읽기 전에 RagJobTimeoutSweeper가 먼저 강제 종료했을 수 있다.
     * 이 조기 반환이 없으면 이미 끝난 job에도 Ollama 호출(수십 초)을 그대로 낭비하게 되는데,
     * Worker/GPU가 1개뿐이라 그 시간만큼 뒤에 대기 중인 다른 job까지 더 늦어진다 — 아래
     * completeSuccess/completeFailed의 조건부 UPDATE는 이 조기 체크 "이후"에 벌어지는 경합(더
     * 좁은 창)까지 막아주는 최종 방어선이다.
     */
    public boolean processJob(Long jobId) {
        RagResponse job = ragResponseRepository.findById(jobId)
            .orElseThrow(() -> new DocGridException(ErrorCode.RAG_ANSWER_NOT_FOUND));
        if (job.getStatus() != ResultStatus.PROCESSING) {
            return false;
        }
        Long queryId = job.getQuery().getId();

        OllamaGenerateResult result;
        try {
            result = ollamaClient.generate(job.getPromptText());
        } catch (DocGridException e) {
            // LLM 장애가 권한 검증을 통과한 벡터 검색 결과까지 숨기지 않도록, 최상위 후보 원문을
            // 그대로 인용해 최소한의 답을 제공한다(extractive fallback). 이 fallback은 비동기 전환
            // 이전과 달리 rag_responses에 그대로 영속화된다 — 나중에 GET/조회로 이 값을 그대로 돌려준다.
            List<VectorSearchCandidate> candidates = loadCandidates(queryId);
            String fallbackAnswer = candidates.isEmpty() ? e.getErrorCode().getMessage()
                : buildExtractiveFallbackAnswer(candidates);
            boolean completed = ragResponseCommandService.completeFailed(job, fallbackAnswer, e.getMessage());
            if (completed) {
                applicationEventPublisher.publishEvent(
                    new RagJobCompletionMetricEvent(Outcome.PROVIDER_FALLBACK)
                );
            }
            log.warn("[RAG] fallback queryId={} errorCode={}", queryId, e.getErrorCode().getCode());
            return completed;
        }

        // LLM이 무관하다고 판단해 안내 문구로만 답했으면, 근거 문서를 같이 보여주지 않는다. 단, 7B
        // 모델이 정상 답변을 끝낸 뒤 지시문을 메아리처럼 이 문구를 덧붙이는 패턴이 관찰됨 — 문구가
        // 답변의 사실상 전부(맨 앞)일 때만 무관으로 취급하고, 정상 답변 중간에 박힌 문구는 그
        // 지점부터 잘라내고 근거 문서는 유지한다. 잘라낸 결과를 그대로 영속화해야 GET 조회 시
        // 사용자에게 보이는 값과 DB 값이 일치한다(동기 시절엔 반환값에만 트리밍이 적용되고 DB엔
        // 원문이 남았는데, 비동기에서는 이 row가 유일한 진실 소스라 그대로 두면 안 된다).
        String answerText = result.answerText();
        boolean noRelevant = false;
        int phraseIndex = answerText != null ? answerText.indexOf(NO_RELEVANT_DOC_PHRASE) : -1;
        if (phraseIndex >= 0) {
            if (answerText.strip().startsWith(NO_RELEVANT_DOC_PHRASE)) {
                noRelevant = true;
            } else {
                log.warn("[RAG] 정상 답변에 무관 안내 문구 혼입, 해당 지점부터 제거: queryId={} phraseIndex={}",
                    queryId, phraseIndex);
                answerText = answerText.substring(0, phraseIndex).strip();
            }
        }

        boolean completed = ragResponseCommandService.completeSuccess(job, new OllamaGenerateResult(
            result.model(), answerText, result.inputTokenCount(), result.outputTokenCount(), result.latencyMs()
        ));
        if (!completed) {
            // RagJobTimeoutSweeper가 이 job을 이미 FAILED로 강제 종료한 뒤라는 뜻이다 — 방금
            // 만든 답변은 이미 아무도 안 볼 결과라, citation 저장도 하지 않고 그대로 물러난다.
            log.info("[RAG] job이 이미 timeout으로 종료됨(경합), 완료 결과 반영 안 함 queryId={} responseId={}",
                queryId, job.getId());
            return false;
        }

        if (!noRelevant) {
            // 1. 프롬프트와 동일한 상한과 검색 순서로 citation 대상을 선택한다.
            List<SearchResult> citationSearchResults =
                searchResultRepository.findByQuery_IdOrderByRankNo(queryId).stream()
                    .limit(MAX_PROMPT_CANDIDATES)
                    .toList();
            // 2. 같은 목록에서 DTO를 만들어 청크와 검색 결과 참조의 대응을 유지한다.
            List<VectorSearchCandidate> citationCandidates = citationSearchResults.stream()
                .map(VectorSearchCandidate::from)
                .toList();
            // 3. 완료 처리가 성공한 답변에만 선택한 후보 순서대로 출처를 저장한다.
            responseCitationCommandService.saveAll(job, citationCandidates, citationSearchResults);
        }
        // 답변과 citation 저장이 모두 끝난 동일 Transaction의 커밋 이후 성공 Counter를 기록한다.
        applicationEventPublisher.publishEvent(new RagJobCompletionMetricEvent(Outcome.SUCCESS));
        log.info("[RAG] done queryId={} responseId={} latencyMs={}", queryId, job.getId(), result.latencyMs());
        return true;
    }

    /**
     * RagJobWorker가 {@link #processJob}을 부르다가 DocGridException이 아닌 예상 못한 예외
     * (버그 등)를 잡았을 때 호출한다. 여기서 FAILED로 확정하지 않으면 job이 영원히
     * PROCESSING으로 남아, 같은 job을 Worker가 계속 다시 집어 무한 재시도하게 된다 —
     * detached entity 버그(#218)와 증상이 같아진다. {@code ifPresent}로 감싸는 이유는 job이
     * 이미 다른 이유로 없어졌을 수 있는 극단적 상황을 방어하기 위함이다.
     *
     * @return 실제로 이 호출로 FAILED 확정이 일어났으면 true. job이 없거나(극단적 상황),
     *         RagJobTimeoutSweeper가 이미 먼저 확정해뒀으면(#288) false — 호출자
     *         (RagJobWorker)는 이 경우 WebSocket 알림을 보내지 않는다.
     */
    public boolean markUnexpectedFailure(Long jobId, String errorMessage) {
        boolean completed = ragResponseRepository.findById(jobId)
            .map(job -> ragResponseCommandService.completeFailed(job, UNEXPECTED_FAILURE_ANSWER_TEXT, errorMessage))
            .orElse(false);
        if (completed) {
            applicationEventPublisher.publishEvent(
                new RagJobCompletionMetricEvent(Outcome.UNEXPECTED_FAILURE)
            );
        }
        return completed;
    }

    /**
     * RagJobTimeoutSweeper가 너무 오래 PROCESSING으로 남은 job을 발견했을 때 호출한다(#286).
     * LLM 호출 실패 fallback과 동일하게 검색 1등 후보를 인용한 답으로 채우되, 실제 종료는
     * {@link RagResponseRepository#forceFailIfProcessing}의 조건부 UPDATE로만 한다 — 그 사이
     * RagJobWorker가 이미 이 job을 정상 완료했다면 영향받은 행이 0건이라 덮어쓰지 않는다.
     *
     * @return 실제로 이 호출로 FAILED 전환이 일어났으면 true, 이미 다른 트랜잭션에서 끝나
     *         아무 일도 하지 않았으면 false.
     */
    public boolean failIfStillProcessing(Long jobId, Long queryId) {
        List<VectorSearchCandidate> candidates = loadCandidates(queryId);
        String fallbackAnswer = candidates.isEmpty()
            ? UNEXPECTED_FAILURE_ANSWER_TEXT
            : buildExtractiveFallbackAnswer(candidates);
        int updated = ragResponseRepository.forceFailIfProcessing(jobId, fallbackAnswer, TIMEOUT_ERROR_MESSAGE);
        if (updated > 0) {
            applicationEventPublisher.publishEvent(new RagJobCompletionMetricEvent(Outcome.TIMEOUT_SWEPT));
        }
        return updated > 0;
    }

    /**
     * LLM 실패 또는 타임아웃 fallback에 사용할 후보를 저장된 검색 결과에서 복원한다.
     * 검색 순서({@code ORDER BY rank_no})를 유지해 최상위 후보의 원문을 인용할 수 있게 한다.
     */
    private List<VectorSearchCandidate> loadCandidates(Long queryId) {
        return searchResultRepository.findByQuery_IdOrderByRankNo(queryId).stream()
            .map(VectorSearchCandidate::from)
            .toList();
    }

    /**
     * Ollama 호출이 실패했을 때, 최상위(유사도 1등) 검색 후보의 원문을 최대
     * {@link #FALLBACK_EXCERPT_MAX_CODE_POINTS}자까지 인용해 최소한의 답을 만든다 — LLM
     * 장애가 이미 권한 검증을 통과한 벡터 검색 결과까지 숨기지 않도록 하기 위함이다.
     */
    private String buildExtractiveFallbackAnswer(List<VectorSearchCandidate> candidates) {
        VectorSearchCandidate top = candidates.get(0);
        String pageSuffix = top.pageNo() != null ? " " + top.pageNo() + "페이지" : "";
        String excerpt = truncate(top.chunkText(), FALLBACK_EXCERPT_MAX_CODE_POINTS);
        return "%s\"%s\" (%s%s)".formatted(LLM_FALLBACK_PREFIX, excerpt, top.documentTitle(), pageSuffix);
    }

    /** 글자 수 상한을 넘으면 말줄임표(…)로 잘라낸다. {@code PromptBuilder}의 동명 메서드와 동일한 방식. */
    private String truncate(String text, int maxCodePoints) {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxCodePoints - 1);
        return text.substring(0, endIndex) + "…";
    }
}
