package com.opensource.docgrid.domain.search.repository;

/**
 * pgvector 검색 결과 네이티브 쿼리 프로젝션.
 *
 * <p>컬럼 alias가 snake_case일 때 Spring Data JPA가 camelCase getter로 자동 매핑한다.
 */
public interface VectorSearchRow {
    Long getEmbeddingId();
    Long getChunkId();
    Long getDocumentId();
    String getChunkText();
    Integer getPageNo();
    String getSectionTitle();
    String getMetadataJson();
    String getDocumentTitle();
    Double getDistance();
}
