package com.opensource.docgrid.domain.rag.dto;

import java.util.List;

import com.opensource.docgrid.domain.search.dto.response.CitationResponse;

/**
 * RAG 답변 하나(텍스트 + 근거 목록)를 담는 그릇.
 *
 * <p>비동기 Job 큐 전환(#218) 이전에는 {@code RagFacade.generate()}가 성공 경로에서 후보 목록을
 * citation으로 변환해 이 타입을 만들었다. #218 이후 {@code RagFacade.processJob()}은 성공/실패
 * 결과를 {@link com.opensource.docgrid.domain.rag.entity.RagResponse}에 직접 영속화하고
 * "실제로 확정이 일어났는지"만 {@code boolean}으로 반환하도록 바뀌어(#288), 그 변환 로직은
 * 더 이상 필요 없어져 제거됐다. 지금 유일하게
 * 쓰이는 경로는 {@link #noContext} — {@code RagFacade.enqueue()}가 검색 후보 0건(NO_CONTEXT)일 때
 * LLM 호출 없이 즉시 만드는 응답이다.
 */
public record RagAnswer(String answerText, List<CitationResponse> citations) {

    public static RagAnswer noContext(String answerText) {
        return new RagAnswer(answerText, List.of());
    }
}
