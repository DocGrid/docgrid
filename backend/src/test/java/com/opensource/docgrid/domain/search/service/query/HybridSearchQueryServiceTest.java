package com.opensource.docgrid.domain.search.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.search.config.HybridSearchProperties;
import com.opensource.docgrid.domain.search.config.VectorSearchProperties;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.LexicalSearchRow;
import com.opensource.docgrid.domain.search.repository.PatternCountRow;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;
import com.opensource.docgrid.domain.search.repository.VectorSearchRow;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridSearchQueryService 단위 테스트")
class HybridSearchQueryServiceTest {

    private static final float[] VECTOR = new float[4];
    private static final Long MODEL_ID = 1L;

    @Mock
    private VectorSearchRepository vectorSearchRepository;

    private final VectorSearchProperties vectorProperties = new VectorSearchProperties();
    private final HybridSearchProperties hybridProperties = new HybridSearchProperties();
    private HybridSearchQueryService service;

    @BeforeEach
    void setUp() {
        // 기본 설계 값: 벡터 유사도 0.45 이상 또는 커버리지 0.50 이상이면 인정, 순위 점수 = 유사도 + 0.1 × 커버리지, 문서당 최대 2개
        service = new HybridSearchQueryService(vectorSearchRepository, vectorProperties, hybridProperties);
    }

    @Test
    @DisplayName("예외 케이스: 읽을 수 있는 문서가 없으면 DB를 조회하지 않고 빈 목록을 반환한다")
    void search_emptyPermittedIds_returnsEmptyWithoutQuery() {
        List<VectorSearchCandidate> result = service.search(VECTOR, "OM은 뭐야?", MODEL_ID, List.of(), 5);

        assertThat(result).isEmpty();
        then(vectorSearchRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정상 케이스: 벡터가 의미로 못 찾는 짧은 용어도 그 단어를 가진 청크가 결과에 들어온다")
    void search_rareTermChunk_isAdmittedDespiteLowSimilarity() {
        // 정답 청크(9번)는 벡터 유사도 0.32로 기준(0.45) 미만이지만 질문 단어 OM을 가진 유일한 청크다(커버리지 1.0).
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.60), vectorRow(2L, 1L, 0.62)));
        stubDocumentFrequency(83, 1);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of(lexicalRow(9L, 2L, 0.68, 1.0)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "OM은 뭐야?", MODEL_ID, List.of(1L, 2L), 5);

        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(9L);
    }

    @Test
    @DisplayName("예외 케이스: 벡터 유사도도 낮고 단어도 거의 안 겹치면 결과가 없다 (문서에 없는 질문)")
    void search_weakBoth_returnsEmpty() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.60), vectorRow(2L, 1L, 0.58)));
        stubDocumentFrequency(83, 5);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of(lexicalRow(2L, 1L, 0.58, 0.20)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, List.of(1L), 5);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("경계 케이스: 기준과 같은 벡터 유사도와 커버리지는 유지한다")
    void search_equalToThresholds_keepsCandidates() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.55)));
        stubDocumentFrequency(83, 3);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of(lexicalRow(2L, 2L, 0.70, 0.50)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, List.of(1L, 2L), 5);

        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("정상 케이스: 작은 커버리지 보너스로는 벡터 유사도가 훨씬 높은 정답이 밀리지 않는다")
    void search_smallBonusDoesNotOverturnLargeVectorGap() {
        // A는 벡터 1위(0.70, 커버리지 0), B는 벡터 유사도 0.62에 커버리지 1.0 → 0.62 + 0.1 = 0.72 < 0.70 + 0.0이 아님을 점검한다.
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.30), vectorRow(2L, 2L, 0.38)));
        stubDocumentFrequency(83, 4);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of(lexicalRow(2L, 2L, 0.38, 0.50)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, List.of(1L, 2L), 5);

        // A: 0.70 + 0.1×0 = 0.70, B: 0.62 + 0.1×0.5 = 0.67 → A가 먼저
        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("정상 케이스: 벡터 유사도가 비슷하면 커버리지 보너스가 순서를 바꾼다")
    void search_bonusBreaksCloseVectorRanks() {
        // A: 0.62 + 0 = 0.62, B: 0.60 + 0.1×1.0 = 0.70 → B가 먼저
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.38), vectorRow(2L, 2L, 0.40)));
        stubDocumentFrequency(83, 2);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of(lexicalRow(2L, 2L, 0.40, 1.0)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, List.of(1L, 2L), 5);

        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("정상 케이스: 커버리지 0인 벡터 후보는 벡터 순서 그대로 남는다 (단어 갈래가 비어도 안전)")
    void search_noLexicalRows_keepsVectorOrder() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.20), vectorRow(2L, 2L, 0.30), vectorRow(3L, 3L, 0.60)));
        stubDocumentFrequency(83, 4);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of());

        List<VectorSearchCandidate> result = service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, List.of(1L, 2L, 3L), 5);

        // 유사도 0.80, 0.70은 통과, 0.40은 기준(0.45) 미만이라 제외
        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("정상 케이스: 문서당 상한은 결합한 순서에 적용된다")
    void search_documentCap_appliedAfterCombining() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(10L, 1L, 0.20), vectorRow(11L, 1L, 0.21), vectorRow(12L, 1L, 0.22),
                vectorRow(20L, 2L, 0.30)));
        stubDocumentFrequency(83, 2);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of(lexicalRow(12L, 1L, 0.22, 1.0)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, List.of(1L, 2L), 5);

        // 12(0.78+0.1)가 가장 위, 10(0.80)이 그다음… 같은 문서 3번째(11)는 상한으로 빠진다.
        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(12L, 10L, 20L);
    }

    @Test
    @DisplayName("정상 케이스: 요청한 topK에서 멈춘다")
    void search_limitsToTopK() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.10), vectorRow(2L, 2L, 0.11), vectorRow(3L, 3L, 0.12)));
        stubDocumentFrequency(83, 2);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of());

        List<VectorSearchCandidate> result = service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, List.of(1L, 2L, 3L), 2);

        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("예외 케이스: 질문에서 뽑을 단어가 없으면 단어 쿼리 없이 벡터 유사도 기준만 적용한다")
    void search_noTerms_usesVectorOnly() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.10), vectorRow(2L, 2L, 0.70)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "뭐 왜 어떻게?", MODEL_ID, List.of(1L, 2L), 5);

        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(1L);
        then(vectorSearchRepository).should(never()).countPatternMatches(anyString(), anyLong(), any());
        then(vectorSearchRepository).should(never()).findLexicalCandidates(anyString(), anyString(), anyString(),
            anyDouble(), anyDouble(), anyLong(), any(), anyList(), anyInt());
    }

    @Test
    @DisplayName("예외 케이스: 검색 범위에 없는 단어뿐이면 단어 갈래를 건너뛴다")
    void search_termsAbsentFromScope_skipsLexicalBranch() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt()))
            .willReturn(List.of(vectorRow(1L, 1L, 0.10)));
        given(vectorSearchRepository.countPatternMatches(anyString(), anyLong(), any()))
            .willReturn(List.of(countRow(0, 83), countRow(1, 0)));

        List<VectorSearchCandidate> result = service.search(VECTOR, "쿠버네티스 알려줘", MODEL_ID, List.of(1L), 5);

        assertThat(result).extracting(VectorSearchCandidate::chunkId).containsExactly(1L);
        then(vectorSearchRepository).should(never()).findLexicalCandidates(anyString(), anyString(), anyString(),
            anyDouble(), anyDouble(), anyLong(), any(), anyList(), anyInt());
    }

    @Test
    @DisplayName("정상 케이스: 희귀한 단어일수록 큰 가중치를 계산해 단어 쿼리에 전달한다")
    void search_passesIdfWeightsAndPatterns() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt())).willReturn(List.of(vectorRow(1L, 1L, 0.10)));
        // 범위 청크 99개, OM은 1개, 방향은 9개 청크에 있다.
        given(vectorSearchRepository.countPatternMatches(anyString(), anyLong(), any()))
            .willReturn(List.of(countRow(0, 99), countRow(1, 1), countRow(2, 9)));
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of());

        service.search(VECTOR, "OM 방향", MODEL_ID, List.of(1L), 5);

        ArgumentCaptor<String> patterns = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> weights = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Double> total = ArgumentCaptor.forClass(Double.class);
        then(vectorSearchRepository).should().findLexicalCandidates(eq("[0.0,0.0,0.0,0.0]"), patterns.capture(),
            weights.capture(), total.capture(), eq(0.5), eq(MODEL_ID), eq(List.of(1L)), eq(List.of(1L)), eq(20));
        assertThat(patterns.getValue()).isEqualTo("(^|[^a-z0-9])om([^a-z0-9]|$)\n방향");
        double omWeight = Math.log(100.0 / 1.5);
        double directionWeight = Math.log(100.0 / 9.5);
        String[] sent = weights.getValue().split("\n");
        assertThat(Double.parseDouble(sent[0])).isCloseTo(omWeight, within(1e-6));
        assertThat(Double.parseDouble(sent[1])).isCloseTo(directionWeight, within(1e-6));
        assertThat(total.getValue()).isCloseTo(omWeight + directionWeight, within(1e-6));
        assertThat(omWeight).isGreaterThan(directionWeight);
    }

    @Test
    @DisplayName("정상 케이스: 단어 갈래와 벡터 갈래가 같은 허용 문서 ID와 모델로 조회한다")
    void search_passesSamePermittedIdsAndModel() {
        given(vectorSearchRepository.findTopK(anyString(), anyLong(), any(), anyInt())).willReturn(List.of(vectorRow(1L, 1L, 0.10)));
        stubDocumentFrequency(83, 2);
        given(vectorSearchRepository.findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(), anyDouble(),
            anyLong(), any(), anyList(), anyInt())).willReturn(List.of());
        List<Long> permitted = List.of(7L, 8L);

        service.search(VECTOR, "서버 설정 알려줘", MODEL_ID, permitted, 5);

        then(vectorSearchRepository).should().findTopK("[0.0,0.0,0.0,0.0]", MODEL_ID, permitted, 20);
        then(vectorSearchRepository).should().countPatternMatches(anyString(), eq(MODEL_ID), eq(permitted));
        then(vectorSearchRepository).should().findLexicalCandidates(anyString(), anyString(), anyString(), anyDouble(),
            anyDouble(), eq(MODEL_ID), eq(permitted), anyList(), eq(20));
    }

    private void stubDocumentFrequency(long scopeChunks, long frequency) {
        // 질문의 모든 단어가 같은 빈도로 들어 있다고 본다. 질문에서 단어 3개까지 대응한다.
        given(vectorSearchRepository.countPatternMatches(anyString(), anyLong(), any()))
            .willReturn(List.of(countRow(0, scopeChunks), countRow(1, frequency), countRow(2, frequency), countRow(3, frequency)));
    }

    private PatternCountRow countRow(int ord, long count) {
        return new PatternCountRow() {
            public Integer getOrd() { return ord; }
            public Long getMatchCount() { return count; }
        };
    }

    private VectorSearchRow vectorRow(Long chunkId, Long documentId, double distance) {
        return new VectorSearchRow() {
            public Long getEmbeddingId() { return chunkId; }
            public Long getChunkId() { return chunkId; }
            public Long getDocumentId() { return documentId; }
            public String getChunkText() { return "청크 " + chunkId; }
            public Integer getPageNo() { return null; }
            public String getSectionTitle() { return null; }
            public String getMetadataJson() { return null; }
            public String getDocumentTitle() { return "문서 " + documentId; }
            public Double getDistance() { return distance; }
        };
    }

    private LexicalSearchRow lexicalRow(Long chunkId, Long documentId, double distance, double coverage) {
        VectorSearchRow base = vectorRow(chunkId, documentId, distance);
        return new LexicalSearchRow() {
            public Long getEmbeddingId() { return base.getEmbeddingId(); }
            public Long getChunkId() { return base.getChunkId(); }
            public Long getDocumentId() { return base.getDocumentId(); }
            public String getChunkText() { return base.getChunkText(); }
            public Integer getPageNo() { return base.getPageNo(); }
            public String getSectionTitle() { return base.getSectionTitle(); }
            public String getMetadataJson() { return base.getMetadataJson(); }
            public String getDocumentTitle() { return base.getDocumentTitle(); }
            public Double getDistance() { return base.getDistance(); }
            public Double getCoverage() { return coverage; }
        };
    }
}
