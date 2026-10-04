package com.opensource.docgrid.domain.search.dto.response;

import java.math.BigDecimal;

import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;

import io.swagger.v3.oas.annotations.media.Schema;

public record SearchResultItem(
    @Schema(description = "순위 (1부터 시작)") int rank,
    @Schema(description = "문서 ID") Long documentId,
    @Schema(description = "매칭된 청크 ID") Long chunkId,
    @Schema(description = "문서 제목") String documentTitle,
    @Schema(description = "매칭된 청크 텍스트") String chunkText,
    @Schema(description = "원본 문서 페이지 번호, 페이지 개념이 없는 형식은 null") Integer pageNo,
    @Schema(description = "Section 경로(예: \"3. 환불 정책 > 3.2 개봉 후 환불\"), 구조 정보가 없는 청크는 null") String sectionPath,
    @Schema(description = "코사인 유사도 (0~1, 높을수록 유사)") BigDecimal similarityScore
) {
    public static SearchResultItem of(int rank, VectorSearchCandidate candidate) {
        return new SearchResultItem(
            rank,
            candidate.documentId(),
            candidate.chunkId(),
            candidate.documentTitle(),
            candidate.chunkText(),
            candidate.pageNo(),
            candidate.sectionPath(),
            candidate.similarityScore()
        );
    }
}
