package com.opensource.docgrid.domain.search.dto;

/**
 * 여러 RAG 답변의 citation 표시 필드를 한 번에 읽어 queryId별로 그룹화하기 위한 조회 전용 DTO다.
 */
public record ConversationCitationProjection(
    Long queryId,
    String citationLabel,
    Long documentId,
    String documentTitle,
    Long chunkId,
    Integer pageNo,
    String quotedText,
    String sectionTitle,
    String metadataJson
) {
}
