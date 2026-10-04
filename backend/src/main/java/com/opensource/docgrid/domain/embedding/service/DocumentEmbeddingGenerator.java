package com.opensource.docgrid.domain.embedding.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.EmbeddingWork;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 준비 Snapshot의 Chunk를 순서가 보존된 Batch로 외부 서버에 전달하고 검증된 Vector Draft를 생성한다.
 *
 * <p>DB Transaction과 JPA Entity를 사용하지 않으며, 모든 Batch가 성공해야 완료 Transaction에
 * 전달할 전체 Draft를 반환한다. 반환 모델과 Vector는 Job 고정 Model 계약으로 검증한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentEmbeddingGenerator {

    private final EmbeddingClient embeddingClient;
    private final AdaptiveEmbeddingBatchPlanner batchPlanner;

    /**
     * 정렬된 Chunk Snapshot을 Batch 순차 호출해 같은 순서의 Embedding Draft로 변환한다.
     */
    public List<DocumentEmbeddingDraft> generate(EmbeddingWork work) {
        validateWork(work);

        List<DocumentEmbeddingDraft> drafts = new ArrayList<>(work.chunks().size());
        // 1. 저장된 Token 수와 실제 Unicode 문자 수를 함께 반영한 순서 보존 Batch를 계획한다.
        List<List<ChunkSnapshot>> batches = batchPlanner.plan(work.chunks());
        for (List<ChunkSnapshot> batchChunks : batches) {
            // 2. 현재 Batch의 Embedding 입력(Section 경로가 있으면 포함)만 전달해 DB Transaction 밖에서 Vector 목록을 생성한다.
            EmbedBatchServerResponse response = embeddingClient.embedBatch(
                batchChunks.stream().map(ChunkSnapshot::embeddingText).toList(),
                batchChunks.size()
            );

            // 3. 응답 모델이 준비 단계에서 고정한 Job Model과 같은지 Batch별로 확인한다.
            if (!Objects.equals(response.model(), work.modelName())) {
                throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
            }

            // 4. Client가 검증한 위치를 원본 Chunk와 결합하고 Vector 저장 계약을 다시 검증한다.
            for (int batchIndex = 0; batchIndex < batchChunks.size(); batchIndex++) {
                ChunkSnapshot chunk = batchChunks.get(batchIndex);
                EmbedBatchItemResponse item = response.embeddings().get(batchIndex);
                float[] vector = item.vector();
                EmbeddingVectorSupport.validate(vector, work.dimension());

                drafts.add(new DocumentEmbeddingDraft(
                    chunk.chunkId(),
                    chunk.chunkIndex(),
                    chunk.contentHash(),
                    vector,
                    EmbeddingVectorSupport.calculateHash(vector)
                ));
            }
        }
        return List.copyOf(drafts);
    }

    /**
     * 외부 Provider 호출에 필요한 버전·모델·차원과 비어 있지 않은 Chunk Snapshot을 검증한다.
     */
    private void validateWork(EmbeddingWork work) {
        if (work == null
            || work.documentVersionId() == null
            || work.embeddingModelId() == null
            || !StringUtils.hasText(work.modelName())
            || work.dimension() <= 0
            || work.chunks() == null
            || work.chunks().isEmpty()) {
            throw new DocGridException(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
        }
    }

}
