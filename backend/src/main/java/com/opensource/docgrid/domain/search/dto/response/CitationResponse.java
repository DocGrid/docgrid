package com.opensource.docgrid.domain.search.dto.response;

import com.opensource.docgrid.domain.document.service.SectionPath;
import com.opensource.docgrid.domain.rag.entity.ResponseCitation;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

import io.swagger.v3.oas.annotations.media.Schema;

public record CitationResponse(
    @Schema(description = "인용 라벨") String label,
    @Schema(description = "출처 문서 ID") Long documentId,
    @Schema(description = "출처 문서 제목") String documentTitle,
    @Schema(description = "근거 chunk ID") Long chunkId,
    @Schema(description = "원본 문서 페이지 번호, 페이지 개념이 없는 형식은 null") Integer pageNo,
    @Schema(description = "Section 경로(예: \"3. 환불 정책 > 3.2 개봉 후 환불\"), 구조 정보가 없는 청크는 null") String sectionPath,
    @Schema(description = "인용된 텍스트") String quotedText
) {
    public static CitationResponse of(int order, VectorSearchCandidate candidate) {
        return new CitationResponse(
            "[" + order + "]",
            candidate.documentId(),
            candidate.documentTitle(),
            candidate.chunkId(),
            candidate.pageNo(),
            candidate.sectionPath(),
            candidate.chunkText()
        );
    }

    // GET /search/{queryId} 재조회 시, 이미 영속화된 response_citations에서 그대로 조립한다.
    public static CitationResponse from(ResponseCitation citation) {
        var chunk = citation.getChunk();
        var document = chunk.getDocumentVersion().getDocument();
        return new CitationResponse(
            citation.getCitationLabel(),
            document.getId(),
            document.getTitle(),
            chunk.getId(),
            citation.getPageNo(),
            SectionPath.display(chunk.getMetadataJson(), chunk.getSectionTitle()),
            citation.getQuotedText()
        );
    }
}
