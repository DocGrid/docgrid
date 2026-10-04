package com.opensource.docgrid.domain.embedding.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.embedding.config.EmbeddingBatchProperties;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 정렬된 Chunk를 개수·Unicode 문자·예상 Token 예산 안의 순차 Embedding Batch로 계획한다.
 *
 * <p>Chunk 자체는 저장·검색의 불변 단위이므로 분할하지 않으며, 단일 Chunk가 예산보다 큰 경우에도
 * 다른 Chunk와 결합하지 않은 단독 Batch로 격리한다. 외부 호출과 Vector 검증은 담당하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AdaptiveEmbeddingBatchPlanner {

    private final EmbeddingBatchProperties properties;

    /**
     * 입력 순서를 유지하면서 다음 Chunk 추가가 어느 안전 상한도 넘지 않는 Batch 목록을 만든다.
     */
    public List<List<ChunkSnapshot>> plan(List<ChunkSnapshot> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }

        List<List<ChunkSnapshot>> batches = new ArrayList<>();
        List<ChunkSnapshot> currentBatch = new ArrayList<>();
        long currentCodePoints = 0;
        long currentTokens = 0;

        for (ChunkSnapshot chunk : chunks) {
            validateChunk(chunk);
            int chunkCodePoints = chunk.embeddingText().codePointCount(0, chunk.embeddingText().length());

            // 1. 비어 있지 않은 Batch에 다음 Chunk를 더했을 때 어느 예산이든 넘으면 먼저 확정한다.
            if (!currentBatch.isEmpty() && exceedsBudget(
                currentBatch.size(),
                currentCodePoints,
                currentTokens,
                chunkCodePoints,
                chunk.tokenCount()
            )) {
                batches.add(List.copyOf(currentBatch));
                currentBatch.clear();
                currentCodePoints = 0;
                currentTokens = 0;
            }

            // 2. Chunk 순서를 바꾸지 않고 현재 Batch에 추가해 응답 Index와 원본 결합 순서를 보존한다.
            currentBatch.add(chunk);
            currentCodePoints += chunkCodePoints;
            currentTokens += chunk.tokenCount();
        }

        // 3. 마지막 Batch도 불변 목록으로 고정해 외부 호출 중 계획이 변경되지 않게 한다.
        if (!currentBatch.isEmpty()) {
            batches.add(List.copyOf(currentBatch));
        }
        return List.copyOf(batches);
    }

    /**
     * 다음 Chunk를 현재 Batch에 추가하면 개수·Code Point·예상 Token 상한 중 하나라도 넘는지 계산한다.
     */
    private boolean exceedsBudget(
        int currentSize,
        long currentCodePoints,
        long currentTokens,
        int nextCodePoints,
        int nextTokens
    ) {
        return currentSize >= properties.getBatchSize()
            || currentCodePoints + nextCodePoints > properties.getMaxCodePoints()
            || currentTokens + nextTokens > properties.getMaxEstimatedTokens();
    }

    /**
     * Batch 계획에 사용할 Chunk에 검색 가능한 Text와 음수가 아닌 Token 추정치가 있는지 확인한다.
     */
    private void validateChunk(ChunkSnapshot chunk) {
        if (chunk == null
            || !StringUtils.hasText(chunk.chunkText())
            || !StringUtils.hasText(chunk.embeddingText())
            || chunk.tokenCount() < 0) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
    }
}
