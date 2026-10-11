package com.opensource.docgrid.domain.search.service.query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.search.config.HybridSearchProperties;
import com.opensource.docgrid.domain.search.config.VectorSearchProperties;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.LexicalSearchRow;
import com.opensource.docgrid.domain.search.repository.PatternCountRow;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;
import com.opensource.docgrid.domain.search.repository.VectorSearchRow;
import com.opensource.docgrid.domain.search.service.query.QueryTermExtractor.QueryTerm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 벡터 유사도에 질문 단어 일치(희귀한 단어일수록 비중이 큼)를 보너스로 더하는 하이브리드 검색 서비스.
 *
 * <p>순위는 {@code 벡터 유사도 + coverageWeight × 커버리지}로 매긴다. 벡터 순서가 주도하고 단어 일치는 작은 보너스라서,
 * 단어가 겹친다는 이유만으로 벡터 1위 정답이 밀리지 않는다. 커버리지는 질문 단어 중 그 청크가 가진 단어의 가중치 합을
 * 전체 합으로 나눈 값이고, 가중치는 검색 범위 안에서 그 단어가 드물수록 크다(IDF). {@code OM}처럼 벡터가 의미로 잡지 못하는
 * 짧은 용어도 그 단어를 가진 청크가 커버리지로 결과에 들어온다. 벡터 유사도가 충분하거나 커버리지가 충분한 청크만 결과로
 * 인정하고, 문서당 청크 상한은 결합한 순서에 적용한다.
 *
 * <p>권한 pre-filter(permittedIds)와 live check는 호출 측({@code SearchFacade})의 책임이며 이 서비스는 전달받은 문서 ID
 * 안에서만 조회한다. 하이브리드 사용 여부는 호출 측이 {@link HybridSearchProperties}로 결정하고, 이 서비스는 기존
 * {@link VectorSearchQueryService}를 대체하지 않고 나란히 존재한다. 단어를 나누는 규칙이 단순해서(조사 제거, 불용어) 형태소
 * 분석이 필요한 질문에서는 단어가 틀리게 잡힐 수 있고, 그 경우 벡터 순서에 가까운 결과가 나온다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridSearchQueryService {

    private static final int SCOPE_TOTAL_ORD = 0;
    private static final List<Long> NO_CHUNK_SENTINEL = List.of(-1L);

    private final VectorSearchRepository vectorSearchRepository;
    private final VectorSearchProperties vectorSearchProperties;
    private final HybridSearchProperties hybridSearchProperties;

    /**
     * 접근 허용 문서 안에서 벡터와 단어 일치 점수를 결합해 관련도·문서 다양성 정책을 적용한 Top-K를 돌려준다.
     *
     * @param queryVector  질문(문맥 포함) 임베딩
     * @param queryText    사용자가 입력한 원문 질문. 단어는 문맥이 섞이지 않은 원문에서 뽑는다
     * @param modelId      문서 임베딩과 같은 모델 ID
     * @param permittedIds 읽기 권한이 확인된 문서 ID
     * @param topK         반환할 최대 개수
     */
    public List<VectorSearchCandidate> search(
        float[] queryVector,
        String queryText,
        Long modelId,
        List<Long> permittedIds,
        int topK
    ) {
        if (permittedIds.isEmpty()) {
            log.debug("[SEARCH] permittedIds empty — skip hybrid search");
            return List.of();
        }

        String vectorStr = VectorSearchQueryService.toVectorString(queryVector);
        int vectorLimit = topK * vectorSearchProperties.getCandidatePoolMultiplier();
        int lexicalLimit = topK * hybridSearchProperties.getLexicalCandidatePoolMultiplier();

        // 1. 벡터 갈래: 기존 쿼리 그대로 후보 풀을 가져온다.
        List<VectorSearchRow> vectorRows = vectorSearchRepository.findTopK(vectorStr, modelId, permittedIds, vectorLimit);

        // 2. 질문 단어에 검색 범위 안의 희귀도(IDF) 가중치를 붙인다.
        WeightedTerms terms = weighTerms(queryText, modelId, permittedIds);

        // 3. 단어 갈래: 단어를 많이 가진 청크와, 벡터 후보 중 단어가 겹치는 청크의 커버리지를 가져온다.
        List<LexicalSearchRow> lexicalRows = terms.isEmpty()
            ? List.of()
            : findLexicalRows(vectorStr, terms, modelId, permittedIds, vectorRows, lexicalLimit);

        // 4. 청크 단위로 합쳐 점수를 매기고 관련도 판정, 문서당 상한, Top-K를 적용한다.
        Map<Long, Scored> merged = merge(vectorRows, lexicalRows);
        List<VectorSearchCandidate> selected = select(merged.values(), topK);

        log.info("[SEARCH] hybrid modelId={} topK={} terms={} vectorPool={} lexicalPool={} merged={} after={}",
            modelId, topK, terms.size(), vectorRows.size(), lexicalRows.size(), merged.size(), selected.size());
        return selected;
    }

    private WeightedTerms weighTerms(String queryText, Long modelId, List<Long> permittedIds) {
        List<QueryTerm> terms = QueryTermExtractor.extract(queryText);
        if (terms.isEmpty()) {
            return WeightedTerms.EMPTY;
        }
        String patterns = terms.stream().map(QueryTerm::regex).collect(Collectors.joining("\n"));
        Map<Integer, Long> counts = new HashMap<>();
        for (PatternCountRow row : vectorSearchRepository.countPatternMatches(patterns, modelId, permittedIds)) {
            counts.put(row.getOrd(), row.getMatchCount());
        }
        long scopeChunks = counts.getOrDefault(SCOPE_TOTAL_ORD, 0L);
        if (scopeChunks == 0) {
            return WeightedTerms.EMPTY;
        }

        // 검색 범위 어디에도 없는 단어는 어떤 청크도 가질 수 없으므로 분모에서 뺀다.
        List<String> keptPatterns = new ArrayList<>();
        List<Double> keptWeights = new ArrayList<>();
        for (int i = 0; i < terms.size(); i++) {
            long documentFrequency = counts.getOrDefault(i + 1, 0L);
            if (documentFrequency > 0) {
                keptPatterns.add(terms.get(i).regex());
                keptWeights.add(Math.log((scopeChunks + 1.0) / (documentFrequency + 0.5)));
            }
        }
        return keptPatterns.isEmpty() ? WeightedTerms.EMPTY : new WeightedTerms(keptPatterns, keptWeights);
    }

    private List<LexicalSearchRow> findLexicalRows(
        String vectorStr, WeightedTerms terms, Long modelId, List<Long> permittedIds,
        List<VectorSearchRow> vectorRows, int lexicalLimit
    ) {
        List<Long> vectorChunkIds = vectorRows.stream().map(VectorSearchRow::getChunkId).toList();
        return vectorSearchRepository.findLexicalCandidates(
            vectorStr, terms.joinedPatterns(), terms.joinedWeights(), terms.totalWeight(),
            hybridSearchProperties.getMinCoverage().doubleValue(), modelId, permittedIds,
            vectorChunkIds.isEmpty() ? NO_CHUNK_SENTINEL : vectorChunkIds, lexicalLimit
        );
    }

    private Map<Long, Scored> merge(List<VectorSearchRow> vectorRows, List<LexicalSearchRow> lexicalRows) {
        Map<Long, Scored> byChunk = new LinkedHashMap<>();
        for (VectorSearchRow row : vectorRows) {
            byChunk.put(row.getChunkId(), new Scored(VectorSearchCandidate.from(row), 0.0));
        }
        // 단어 쪽에서 온 청크도 쿼리가 함께 계산한 벡터 거리로 유사도를 갖는다. 벡터 후보에도 같은 커버리지를 붙인다.
        for (LexicalSearchRow row : lexicalRows) {
            byChunk.compute(row.getChunkId(), (chunkId, existing) -> new Scored(
                existing == null ? VectorSearchCandidate.from(row) : existing.candidate, row.getCoverage()));
        }
        return byChunk;
    }

    private List<VectorSearchCandidate> select(Iterable<Scored> merged, int topK) {
        BigDecimal vectorMin = hybridSearchProperties.getVectorMinSimilarity();
        double minCoverage = hybridSearchProperties.getMinCoverage().doubleValue();
        double coverageWeight = hybridSearchProperties.getCoverageWeight().doubleValue();
        int maxPerDocument = vectorSearchProperties.getMaxChunksPerDocument();

        // 벡터 유사도가 충분하거나 질문 단어를 충분히 가진 청크만 남기고 결합 점수로 정렬한다.
        List<Scored> relevant = new ArrayList<>();
        for (Scored entry : merged) {
            boolean vectorRelevant = entry.candidate.similarityScore().compareTo(vectorMin) >= 0;
            boolean lexicalRelevant = entry.coverage >= minCoverage;
            if (vectorRelevant || lexicalRelevant) {
                entry.score = entry.candidate.similarityScore().doubleValue() + coverageWeight * entry.coverage;
                relevant.add(entry);
            }
        }
        relevant.sort(
            Comparator.comparingDouble((Scored s) -> s.score).reversed()
                .thenComparing((Scored s) -> s.candidate.similarityScore(), Comparator.reverseOrder())
                .thenComparing(s -> s.candidate.chunkId())
        );

        // 한 문서가 결과를 독점하지 않도록 결합한 순서대로 문서당 상한을 적용하고 Top-K에서 멈춘다.
        List<VectorSearchCandidate> selected = new ArrayList<>(topK);
        Map<Long, Integer> documentCounts = new HashMap<>();
        for (Scored entry : relevant) {
            Long documentId = entry.candidate.documentId();
            int count = documentCounts.getOrDefault(documentId, 0);
            if (count >= maxPerDocument) {
                continue;
            }
            selected.add(entry.candidate);
            documentCounts.put(documentId, count + 1);
            if (selected.size() == topK) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    /** 한 청크의 후보와 단어 커버리지를 모으는 서비스 내부 전용 값. 외부 응답 DTO와 분리해 파급을 막는다. */
    private static final class Scored {
        private final VectorSearchCandidate candidate;
        private final double coverage;
        private double score;

        private Scored(VectorSearchCandidate candidate, double coverage) {
            this.candidate = candidate;
            this.coverage = coverage;
        }
    }

    /** 검색 범위에 실제로 있는 질문 단어의 정규식 패턴과 희귀도(IDF) 가중치. 빈 값이면 단어 갈래를 건너뛴다. */
    private record WeightedTerms(List<String> patterns, List<Double> weights) {

        static final WeightedTerms EMPTY = new WeightedTerms(List.of(), List.of());

        boolean isEmpty() {
            return patterns.isEmpty();
        }

        int size() {
            return patterns.size();
        }

        String joinedPatterns() {
            return String.join("\n", patterns);
        }

        String joinedWeights() {
            return weights.stream().map(w -> String.format(Locale.ROOT, "%.10f", w)).collect(Collectors.joining("\n"));
        }

        double totalWeight() {
            return weights.stream().mapToDouble(Double::doubleValue).sum();
        }
    }
}
