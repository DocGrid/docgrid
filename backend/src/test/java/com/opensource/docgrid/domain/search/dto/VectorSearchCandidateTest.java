package com.opensource.docgrid.domain.search.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.document.entity.Document;
import com.opensource.docgrid.domain.document.entity.DocumentChunk;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.rag.entity.ResponseCitation;
import com.opensource.docgrid.domain.search.dto.response.CitationResponse;
import com.opensource.docgrid.domain.search.dto.response.SearchResultItem;
import com.opensource.docgrid.domain.search.entity.SearchResult;
import com.opensource.docgrid.domain.search.repository.VectorSearchRow;

/**
 * 검색 후보와 응답 DTO가 Chunk의 Section 경로를 새 검색·저장 결과 조회 경로 모두에서 같게 전달하는지 검증한다.
 */
@DisplayName("Section 경로 전달 테스트")
class VectorSearchCandidateTest {

    private static final String PATH_JSON =
        "{\"headingPath\":[\"3. 환불 정책\",\"3.2 개봉 후 환불\"],\"headingLevel\":2}";

    @Test
    @DisplayName("새 검색 결과 행은 Metadata 경로를 잇고 없으면 Section Title로 대체한다")
    void fromRow_buildsSectionPath() {
        assertThat(VectorSearchCandidate.from(row("3.2 개봉 후 환불", PATH_JSON)).sectionPath())
            .isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불");
        assertThat(VectorSearchCandidate.from(row("3.2 개봉 후 환불", null)).sectionPath())
            .isEqualTo("3.2 개봉 후 환불");
        assertThat(VectorSearchCandidate.from(row(null, null)).sectionPath()).isNull();
    }

    @Test
    @DisplayName("저장된 검색 결과를 다시 조립해도 같은 Section 경로를 만든다")
    void fromSearchResult_buildsSectionPath() {
        DocumentChunk chunk = chunk("3.2 개봉 후 환불", PATH_JSON);
        SearchResult result = mock(SearchResult.class);
        given(result.getChunk()).willReturn(chunk);
        given(result.getSimilarityScore()).willReturn(new BigDecimal("0.800000"));

        assertThat(VectorSearchCandidate.from(result).sectionPath()).isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불");
    }

    @Test
    @DisplayName("검색 결과 항목과 출처 응답은 후보의 Section 경로를 그대로 담는다")
    void responses_carrySectionPath() {
        VectorSearchCandidate candidate = VectorSearchCandidate.from(row("3.2 개봉 후 환불", PATH_JSON));

        assertThat(SearchResultItem.of(1, candidate).sectionPath()).isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불");
        assertThat(CitationResponse.of(1, candidate).sectionPath()).isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불");
    }

    @Test
    @DisplayName("저장된 출처를 다시 읽을 때도 Chunk의 Section 경로를 응답에 담는다")
    void citationFromEntity_buildsSectionPath() {
        DocumentChunk chunk = chunk("3.2 개봉 후 환불", PATH_JSON);
        ResponseCitation citation = mock(ResponseCitation.class);
        given(citation.getChunk()).willReturn(chunk);
        given(citation.getCitationLabel()).willReturn("[1]");

        assertThat(CitationResponse.from(citation).sectionPath()).isEqualTo("3. 환불 정책 > 3.2 개봉 후 환불");
    }

    @Test
    @DisplayName("경로 Metadata 도입 전 Chunk는 Section 경로 없이도 후보를 만든다")
    void legacyConstructor_leavesSectionPathNull() {
        VectorSearchCandidate candidate = new VectorSearchCandidate(
            1L, 2L, 3L, "본문", null, "문서", BigDecimal.ONE
        );

        assertThat(candidate.sectionPath()).isNull();
    }

    private DocumentChunk chunk(String sectionTitle, String metadataJson) {
        Document document = mock(Document.class);
        given(document.getId()).willReturn(3L);
        given(document.getTitle()).willReturn("문서");
        DocumentVersion version = mock(DocumentVersion.class);
        given(version.getDocument()).willReturn(document);
        DocumentChunk chunk = mock(DocumentChunk.class);
        given(chunk.getId()).willReturn(2L);
        given(chunk.getChunkText()).willReturn("본문");
        given(chunk.getSectionTitle()).willReturn(sectionTitle);
        given(chunk.getMetadataJson()).willReturn(metadataJson);
        given(chunk.getDocumentVersion()).willReturn(version);
        return chunk;
    }

    private VectorSearchRow row(String sectionTitle, String metadataJson) {
        return new VectorSearchRow() {
            public Long getEmbeddingId() { return 1L; }
            public Long getChunkId() { return 2L; }
            public Long getDocumentId() { return 3L; }
            public String getChunkText() { return "본문"; }
            public Integer getPageNo() { return null; }
            public String getSectionTitle() { return sectionTitle; }
            public String getMetadataJson() { return metadataJson; }
            public String getDocumentTitle() { return "문서"; }
            public Double getDistance() { return 0.2; }
        };
    }
}
