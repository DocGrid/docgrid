package com.opensource.docgrid.domain.search.service.query;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opensource.docgrid.domain.search.config.VectorSearchProperties;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * pgvector 코사인 거리 검색 서비스 (F-SEARCH-05).
 *
 * <p>permittedIds가 빈 목록이면 DB를 조회하지 않고 즉시 빈 목록을 반환한다.
 * queryVector는 '[v1,v2,...]' 형식 문자열로 변환해 네이티브 쿼리에 전달한다. 제한된 후보 풀을
 * 기존 유사도 순서로 조회하고, 최소 유사도와 문서별 청크 상한을 통과한 Top-K만 다음 단계로 전달한다.
 */
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSearchQueryService {

    private final VectorSearchRepository vectorSearchRepository;
    private final VectorSearchProperties vectorSearchProperties;

    /**
     * 접근 허용 문서 안에서 Query Vector와 같은 모델의 후보를 검색하고 관련성·문서 다양성 정책을 적용한다.
     */
    public List<VectorSearchCandidate> search(
        float[] queryVector,
        Long modelId,
        List<Long> permittedIds,
        int topK
    ) {
        if (permittedIds.isEmpty()) {
            log.debug("[SEARCH] permittedIds empty — skip vector search");
            return List.of();
        }

        String vectorStr = toVectorString(queryVector);
        int candidateLimit = topK * vectorSearchProperties.getCandidatePoolMultiplier();
        log.debug("[SEARCH] vector search modelId={} permittedCount={} topK={} candidateLimit={}",
            modelId, permittedIds.size(), topK, candidateLimit);

        // 1. HNSW 실행계획을 유지하면서 문서 다양성 필터에 필요한 후보만 제한적으로 더 조회한다.
        List<VectorSearchCandidate> candidates = vectorSearchRepository.findTopK(
                vectorStr, modelId, permittedIds, candidateLimit)
            .stream()
            .map(VectorSearchCandidate::from)
            .toList();

        // 2. 관련성 미달과 한 문서의 과도한 청크를 제거한 뒤 요청한 Top-K에서 중단한다.
        BigDecimal minSimilarity = vectorSearchProperties.getMinSimilarity();
        int maxChunksPerDocument = vectorSearchProperties.getMaxChunksPerDocument();

        // 3. 위 두 조건을 통과한 후보만 topK개가 채워질 때까지 순서대로 담는다.
        List<VectorSearchCandidate> selected = selectCandidates(
            candidates, topK, minSimilarity, maxChunksPerDocument);

        log.info("[SEARCH] candidate filter modelId={} topK={} candidateLimit={} minSimilarity={} "
                + "maxChunksPerDocument={} before={} after={}",
            modelId, topK, candidateLimit, minSimilarity, maxChunksPerDocument, candidates.size(), selected.size());
        return selected;
    }

    /**
     * 거리순 후보에서 최소 유사도와 문서별 Chunk 상한을 만족하는 Top-K를 순서대로 선택한다.
     */
    private List<VectorSearchCandidate> selectCandidates(
        List<VectorSearchCandidate> candidates,
        int topK,
        BigDecimal minSimilarity,
        int maxChunksPerDocument
    ) {
        List<VectorSearchCandidate> selected = new ArrayList<>(topK);
        Map<Long, Integer> documentCounts = new HashMap<>();

        for (VectorSearchCandidate candidate : candidates) {
            // 1. 최소 유사도보다 낮은 후보는 검색 결과에서 제외한다.
            if (candidate.similarityScore().compareTo(minSimilarity) < 0) {
                continue;
            }

            // 2. 한 문서의 Chunk가 결과를 독점하지 않도록 문서별 상한을 적용한다.
            int documentCount = documentCounts.getOrDefault(candidate.documentId(), 0);
            if (documentCount >= maxChunksPerDocument) {
                continue;
            }

            // 3. 원래 Vector 유사도 순서를 유지하며 선택하고 요청한 Top-K가 차면 즉시 끝낸다.
            selected.add(candidate);
            documentCounts.put(candidate.documentId(), documentCount + 1);
            if (selected.size() == topK) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    /**
     * float 배열을 pgvector Native Query가 CAST할 수 있는 대괄호 Vector 문자열로 직렬화한다.
     */
    static String toVectorString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
