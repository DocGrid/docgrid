package com.opensource.docgrid.domain.search.dto;

import com.opensource.docgrid.domain.search.enums.ResultStatus;

/**
 * 여러 검색 질문의 RAG 처리 상태와 확정 답변을 queryId별로 조립하기 위한 조회 전용 DTO다.
 */
public record ConversationRagResponseProjection(
    Long queryId,
    ResultStatus status,
    String answerText,
    String sourceDocumentIdsJson
) {
    /** 출처를 알 수 없는 이전 행은 명시적으로 NULL을 유지한다. */
    public ConversationRagResponseProjection(Long queryId, ResultStatus status, String answerText) {
        this(queryId, status, answerText, null);
    }
}
