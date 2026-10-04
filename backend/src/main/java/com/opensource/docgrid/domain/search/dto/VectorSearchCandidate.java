package com.opensource.docgrid.domain.search.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.opensource.docgrid.domain.document.service.SectionPath;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.repository.VectorSearchRow;

/**
 * 벡터 검색 후보 1건을 담는 내부 전달 객체.
 *
 * <p>similarityScore = max(0, 1 - cosineDistance) 로 변환해 저장한다.
 * pgvector `<=>` 코사인 거리는 이론상 [0, 2] 범위이므로 0으로 clamp해 음수를 방어한다.
 * live check(F-SEARCH-06) 통과 후 SearchResult 저장 및 응답 조립에 재사용한다.
 */
public record VectorSearchCandidate(
    Long embeddingId,
    Long chunkId,
    Long documentId,
    String chunkText,
    Integer pageNo,
    String documentTitle,
    BigDecimal similarityScore,
    String sectionPath
) {

    /**
     * Section 경로 없이 후보를 만든다. 경로 개념이 없는 형식(PDF·TXT)이나 경로를 알 수 없는 호출에 쓴다.
     */
    public VectorSearchCandidate(
        Long embeddingId,
        Long chunkId,
        Long documentId,
        String chunkText,
        Integer pageNo,
        String documentTitle,
        BigDecimal similarityScore
    ) {
        this(embeddingId, chunkId, documentId, chunkText, pageNo, documentTitle, similarityScore, null);
    }

    /**
     * pgvector가 방금 계산한 raw 검색 결과(VectorSearchRow)에서 조립한다.
     * POST /search로 새로 검색할 때 쓰이는 경로 — {@link #from(SearchResult)}와 구분할 것.
     */
    public static VectorSearchCandidate from(VectorSearchRow row) {
        BigDecimal score = BigDecimal.ONE
            .subtract(BigDecimal.valueOf(row.getDistance()))
            .max(BigDecimal.ZERO)
            .setScale(6, RoundingMode.HALF_UP);
        return new VectorSearchCandidate(
            row.getEmbeddingId(),
            row.getChunkId(),
            row.getDocumentId(),
            row.getChunkText(),
            row.getPageNo(),
            row.getDocumentTitle(),
            score,
            SectionPath.display(row.getMetadataJson(), row.getSectionTitle())
        );
    }

    /**
     * 이미 DB에 저장된 SearchResult(+chunk)에서 거꾸로 재조립한다. pgvector를 다시 조회하지 않고
     * 저장된 similarityScore를 그대로 재사용한다. GET /search/{queryId} 재조회(SearchAnswerQueryService)와
     * RAG Worker의 비동기 citation 재구성이 이 경로를 쓴다 — {@link #from(VectorSearchRow)}와 구분할 것.
     */
    public static VectorSearchCandidate from(SearchResult result) {
        var chunk = result.getChunk();
        var document = chunk.getDocumentVersion().getDocument();
        return new VectorSearchCandidate(
            result.getEmbedding() != null ? result.getEmbedding().getId() : null,
            chunk.getId(),
            document.getId(),
            chunk.getChunkText(),
            chunk.getPageNo(),
            document.getTitle(),
            result.getSimilarityScore(),
            SectionPath.display(chunk.getMetadataJson(), chunk.getSectionTitle())
        );
    }
}
