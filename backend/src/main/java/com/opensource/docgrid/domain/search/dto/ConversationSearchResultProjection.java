package com.opensource.docgrid.domain.search.dto;

import java.math.BigDecimal;

/**
 * 여러 검색 질문의 결과를 한 번에 읽고 queryId별 화면 응답으로 그룹화하기 위한 조회 전용 DTO다.
 */
public record ConversationSearchResultProjection(
    Long queryId,
    Integer rankNo,
    Long documentId,
    Long chunkId,
    String documentTitle,
    String chunkText,
    Integer pageNo,
    BigDecimal similarityScore,
    String sectionTitle,
    String metadataJson
) {
}
