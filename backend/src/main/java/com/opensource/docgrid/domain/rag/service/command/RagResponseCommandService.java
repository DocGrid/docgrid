package com.opensource.docgrid.domain.rag.service.command;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.rag.dto.OllamaGenerateResult;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

import lombok.RequiredArgsConstructor;

/**
 * RAG 최종 답변 저장 서비스 (F-RAG-03).
 *
 * <p>비동기 Job 큐 전환(#218) 이후에는 검색 직후 PROCESSING row를 먼저 저장해두고(createPending),
 * Worker가 LLM 생성을 마친 뒤 현재 claim token을 조건으로 같은 row를 채운다 — SearchQuery의
 * PROCESSING 선저장 패턴과 동일해졌다. 이 둘은 RagJobTimeoutSweeper와의 경합(#288) 때문에
 * 엔티티 dirty checking이 아니라 {@code RagResponseRepository}의 조건부 UPDATE(WHERE
 * status=PROCESSING AND claim_token=...)로 직접 확정한다.
 */
@Transactional
@Service
@RequiredArgsConstructor
public class RagResponseCommandService {

    private static final String LLM_PROVIDER = "Ollama";
    private static final String NO_CONTEXT_ANSWER_TEXT = "관련 문서를 찾지 못했습니다.";

    private final RagResponseRepository ragResponseRepository;

    /**
     * 검색 직후, 프롬프트 조립까지만 동기로 끝내고 PROCESSING 상태로 Job 큐에 올린다.
     * 아직 답이 없으므로 answerText/llmModelName은 채우지 않는다 — 실제 LLM 호출은 나중에
     * RagJobWorker가 한다.
     */
    public RagResponse createPending(SearchQuery query, String promptText) {
        return createPending(query, promptText, null);
    }

    /** 새 입력의 전체 출처를 본문과 같은 트랜잭션에서 저장한다. */
    public RagResponse createPending(SearchQuery query, String promptText, String sources) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .llmProvider(LLM_PROVIDER)
            .promptText(promptText)
            .sourceDocumentIdsJson(sources)
            .status(ResultStatus.PROCESSING)
            .build();
        return ragResponseRepository.save(ragResponse);
    }

    /**
     * 검색 결과가 0건(NO_CONTEXT)이라 LLM 호출 자체를 생략한 경우. PROCESSING을 거치지 않고
     * 곧바로 SUCCESS로 확정한다 — 실패가 아니라 "검색은 됐지만 근거가 없다"는 정상 결과이기
     * 때문이다. citation 없이 고정 문구만 기록한다.
     */
    public RagResponse createNoContext(SearchQuery query) {
        RagResponse ragResponse = RagResponse.builder()
            .query(query)
            .sourceDocumentIdsJson("[]")
            .answerText(NO_CONTEXT_ANSWER_TEXT)
            .status(ResultStatus.SUCCESS)
            .build();
        return ragResponseRepository.save(ragResponse);
    }

    /** 모델 입력 갱신도 token·lease fencing을 적용해 이전 작업자의 변경을 차단한다. */
    public boolean preparePromptOwned(RagResponse job, String token, String prompt, String sources) {
        return ragResponseRepository.preparePromptIfOwned(job.getId(), token, LocalDateTime.now(), prompt, sources) == 1;
    }

    /** 현재 claim token의 lease가 유효할 때만 성공 결과를 확정해 timeout·재선점 경합을 막는다. */
    public boolean completeSuccessOwned(RagResponse ragResponse, String token, OllamaGenerateResult result) {
        return ragResponseRepository.completeSuccessIfOwned(
            ragResponse.getId(), token, LocalDateTime.now(), result.answerText(), result.model(),
            result.inputTokenCount(), result.outputTokenCount(), result.latencyMs()
        ) == 1;
    }

    /** 모델 실패도 현재 token으로만 확정해 이전 소유자의 늦은 오류가 답변을 덮어쓰지 못하게 한다. */
    public boolean completeFailedOwned(RagResponse ragResponse, String token,
                                       String fallbackAnswerText, String errorMessage) {
        return ragResponseRepository.completeFailureIfOwned(
            ragResponse.getId(), token, LocalDateTime.now(), fallbackAnswerText, errorMessage
        ) == 1;
    }
}
