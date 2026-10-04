package com.opensource.docgrid.domain.search.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.search.config.VectorSearchProperties;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;
import com.opensource.docgrid.domain.search.repository.VectorSearchRow;

@ExtendWith(MockitoExtension.class)
@DisplayName("VectorSearchQueryService 단위 테스트")
class VectorSearchQueryServiceTest {

    private VectorSearchQueryService vectorSearchQueryService;

    @Mock
    private VectorSearchRepository vectorSearchRepository;

    private final VectorSearchProperties vectorSearchProperties = new VectorSearchProperties();

    private static final float[] VECTOR = new float[1024];
    private static final Long MODEL_ID = 1L;

    @BeforeEach
    void setUp() {
        vectorSearchQueryService = new VectorSearchQueryService(vectorSearchRepository, vectorSearchProperties);
    }

    @Test
    @DisplayName("permittedIds가 비어있으면 DB 조회 없이 빈 목록을 반환한다")
    void search_emptyPermittedIds_returnsEmptyWithoutQuery() {
        List<VectorSearchCandidate> result = vectorSearchQueryService.search(VECTOR, MODEL_ID, List.of(), 5);

        assertThat(result).isEmpty();
        then(vectorSearchRepository).should(never()).findTopK(anyString(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("정상 케이스: 임계값을 초과한 쿼리 결과를 VectorSearchCandidate로 변환해 반환한다")
    void search_aboveThreshold_returnsMappedCandidates() {
        VectorSearchRow row = mockRow(1L, 2L, 3L, "청크 텍스트", 1, "문서 제목", 0.2);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(row));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L), 5
        );

        assertThat(result).hasSize(1);
        assertThat(result.get(0).documentId()).isEqualTo(3L);
        assertThat(result.get(0).chunkText()).isEqualTo("청크 텍스트");
        // similarityScore = 1 - 0.2 = 0.8
        assertThat(result.get(0).similarityScore().doubleValue()).isEqualTo(0.8, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("경계 케이스: 최소 유사도와 같은 후보를 유지한다")
    void search_equalToThreshold_keepsCandidate() {
        VectorSearchRow row = mockRow(1L, 2L, 3L, "경계 청크", 1, "문서 제목", 0.7);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(row));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L), 5
        );

        assertThat(result).singleElement()
            .extracting(VectorSearchCandidate::similarityScore)
            .isEqualTo(new BigDecimal("0.300000"));
    }

    @Test
    @DisplayName("필터링 케이스: 최소 유사도보다 낮은 후보를 제거한다")
    void search_belowThreshold_removesCandidate() {
        VectorSearchRow row = mockRow(1L, 2L, 3L, "무관한 청크", 1, "문서 제목", 0.71);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(row));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L), 5
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("필터링 케이스: 혼합된 Top-K에서 통과 후보의 기존 순서를 유지한다")
    void search_mixedScores_returnsQualifiedCandidatesInOriginalOrder() {
        VectorSearchRow first = mockRow(1L, 11L, 3L, "첫 번째", 1, "문서", 0.1);
        VectorSearchRow second = mockRow(2L, 12L, 3L, "두 번째", 2, "문서", 0.4);
        VectorSearchRow rejected = mockRow(3L, 13L, 3L, "제외", 3, "문서", 0.8);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(first, second, rejected));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L), 5
        );

        assertThat(result).extracting(VectorSearchCandidate::chunkId)
            .containsExactly(11L, 12L);
    }

    @Test
    @DisplayName("다양성 케이스: 같은 문서는 설정된 청크 수만 유지하고 다른 문서 후보를 포함한다")
    void search_sameDocumentCandidates_limitsChunksAndKeepsOtherDocuments() {
        VectorSearchRow first = mockRow(1L, 11L, 3L, "첫 번째", 1, "문서 A", 0.1);
        VectorSearchRow second = mockRow(2L, 12L, 3L, "두 번째", 2, "문서 A", 0.2);
        VectorSearchRow duplicate = mockRow(3L, 13L, 3L, "세 번째", 3, "문서 A", 0.25);
        VectorSearchRow otherDocument = mockRow(4L, 21L, 4L, "다른 문서", 1, "문서 B", 0.3);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(first, second, duplicate, otherDocument));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L, 4L), 4
        );

        assertThat(result).extracting(VectorSearchCandidate::chunkId)
            .containsExactly(11L, 12L, 21L);
        then(vectorSearchRepository).should().findTopK(anyString(), eq(MODEL_ID), eq(List.of(3L, 4L)), eq(16));
    }

    @Test
    @DisplayName("설정 케이스: 문서별 청크 상한을 변경하면 변경한 수만 유지한다")
    void search_customDocumentLimit_appliesConfiguredValue() {
        vectorSearchProperties.setMaxChunksPerDocument(1);
        VectorSearchRow first = mockRow(1L, 11L, 3L, "첫 번째", 1, "문서", 0.1);
        VectorSearchRow second = mockRow(2L, 12L, 3L, "두 번째", 2, "문서", 0.2);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(first, second));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L), 5
        );

        assertThat(result).extracting(VectorSearchCandidate::chunkId)
            .containsExactly(11L);
    }

    @Test
    @DisplayName("설정 케이스: 변경한 최소 유사도를 검색에 적용한다")
    void search_customThreshold_appliesConfiguredValue() {
        vectorSearchProperties.setMinSimilarity(new BigDecimal("0.50"));
        VectorSearchRow row = mockRow(1L, 2L, 3L, "청크", 1, "문서 제목", 0.6);
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(row));

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(3L), 5
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("결과가 없으면 빈 목록을 반환한다")
    void search_noResults_returnsEmpty() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of());

        List<VectorSearchCandidate> result = vectorSearchQueryService.search(
            VECTOR, MODEL_ID, List.of(1L), 5
        );

        assertThat(result).isEmpty();
    }

    private VectorSearchRow mockRow(Long embeddingId, Long chunkId, Long documentId,
                                     String chunkText, Integer pageNo, String documentTitle, double distance) {
        return new VectorSearchRow() {
            public Long getEmbeddingId() { return embeddingId; }
            public Long getChunkId() { return chunkId; }
            public Long getDocumentId() { return documentId; }
            public String getChunkText() { return chunkText; }
            public Integer getPageNo() { return pageNo; }
            public String getSectionTitle() { return null; }
            public String getMetadataJson() { return null; }
            public String getDocumentTitle() { return documentTitle; }
            public Double getDistance() { return distance; }
        };
    }
}
