package com.opensource.docgrid.domain.embedding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.config.EmbeddingBatchProperties;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.EmbeddingWork;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * Chunk Vector의 Batch 순차 생성, 검증 실패 중단, Hash와 Draft 불변성 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentEmbeddingGenerator 테스트")
class DocumentEmbeddingGeneratorTest {

    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";
    private static final String VECTOR_HASH =
        "efde6f1eea3d119bf25d4a0e2ce188572e80986e14d85c3f2a9528fa66f8731f";

    @Mock private EmbeddingClient embeddingClient;

    @Test
    @DisplayName("Chunk 순서대로 Batch 호출하고 검증된 Vector와 SHA-256 Hash를 반환한다")
    void generate_callsSequentiallyAndReturnsDrafts() {
        DocumentEmbeddingGenerator generator = generator(2);
        given(embeddingClient.embedBatch(List.of("첫 번째", "두 번째"), 2))
            .willReturn(response(
                item(0, 1.0f, -2.0f),
                item(1, 0.5f, 0.25f)
            ));

        List<DocumentEmbeddingDraft> drafts = generator.generate(work(
            chunk(20L, 0, "첫 번째"),
            chunk(21L, 1, "두 번째")
        ));

        then(embeddingClient).should().embedBatch(List.of("첫 번째", "두 번째"), 2);
        assertThat(drafts)
            .extracting(DocumentEmbeddingDraft::chunkId)
            .containsExactly(20L, 21L);
        assertThat(drafts.get(0).vector()).containsExactly(1.0f, -2.0f);
        assertThat(drafts.get(0).vectorHash()).isEqualTo(VECTOR_HASH);
    }

    @Test
    @DisplayName("Section 경로가 붙은 Embedding 입력을 서버에 보내고 Draft는 원문 Chunk의 Hash를 유지한다")
    void generate_sendsEmbeddingTextAndKeepsChunkContentHash() {
        DocumentEmbeddingGenerator generator = generator(2);
        given(embeddingClient.embedBatch(List.of("1장 > 1.1절\n본문"), 1))
            .willReturn(response(item(0, 1.0f, -2.0f)));

        List<DocumentEmbeddingDraft> drafts = generator.generate(work(
            new ChunkSnapshot(20L, 0, "본문", "1장 > 1.1절\n본문", 1, CONTENT_HASH)
        ));

        then(embeddingClient).should().embedBatch(List.of("1장 > 1.1절\n본문"), 1);
        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.chunkId()).isEqualTo(20L);
            assertThat(draft.contentHash()).isEqualTo(CONTENT_HASH);
        });
    }

    @Test
    @DisplayName("Chunk가 설정 크기보다 많으면 Batch를 순서대로 나누고 Draft 순서를 보존한다")
    void generate_splitsChunksAndPreservesDraftOrder() {
        DocumentEmbeddingGenerator generator = generator(2);
        given(embeddingClient.embedBatch(List.of("첫 번째", "두 번째"), 2))
            .willReturn(response(item(0, 1.0f, 0.0f), item(1, 2.0f, 0.0f)));
        given(embeddingClient.embedBatch(List.of("세 번째"), 1))
            .willReturn(response(item(0, 3.0f, 0.0f)));

        List<DocumentEmbeddingDraft> drafts = generator.generate(work(
            chunk(20L, 0, "첫 번째"),
            chunk(21L, 1, "두 번째"),
            chunk(22L, 2, "세 번째")
        ));

        InOrder callOrder = inOrder(embeddingClient);
        callOrder.verify(embeddingClient).embedBatch(List.of("첫 번째", "두 번째"), 2);
        callOrder.verify(embeddingClient).embedBatch(List.of("세 번째"), 1);
        assertThat(drafts)
            .extracting(DocumentEmbeddingDraft::chunkId)
            .containsExactly(20L, 21L, 22L);
        assertThat(drafts)
            .extracting(draft -> draft.vector()[0])
            .containsExactly(1.0f, 2.0f, 3.0f);
    }

    @Test
    @DisplayName("응답 모델이 Job 고정 모델과 다르면 Vector를 Draft로 만들지 않는다")
    void generate_rejectsModelMismatch() {
        DocumentEmbeddingGenerator generator = generator(2);
        given(embeddingClient.embedBatch(List.of("첫 번째"), 1))
            .willReturn(new EmbedBatchServerResponse(
                "different-model",
                List.of(item(0, 1.0f, 0.0f))
            ));

        assertThatThrownBy(() -> generator.generate(work(chunk(20L, 0, "첫 번째"))))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT));
    }

    @Test
    @DisplayName("두 번째 Batch가 실패하면 뒤 Batch를 호출하지 않는다")
    void generate_stopsAfterBatchFailure() {
        DocumentEmbeddingGenerator generator = generator(1);
        given(embeddingClient.embedBatch(List.of("첫 번째"), 1))
            .willReturn(response(item(0, 1.0f, 0.0f)));
        given(embeddingClient.embedBatch(List.of("두 번째"), 1))
            .willThrow(new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE));

        assertThatThrownBy(() -> generator.generate(work(
            chunk(20L, 0, "첫 번째"),
            chunk(21L, 1, "두 번째"),
            chunk(22L, 2, "세 번째")
        )))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE));

        then(embeddingClient).should(never()).embedBatch(List.of("세 번째"), 1);
    }

    @Test
    @DisplayName("첫 Vector 차원이 다르면 다음 Batch를 호출하지 않고 실패한다")
    void generate_stopsWhenDimensionMismatches() {
        DocumentEmbeddingGenerator generator = generator(1);
        given(embeddingClient.embedBatch(List.of("첫 번째"), 1))
            .willReturn(response(item(0, 1.0f)));

        assertThatThrownBy(() -> generator.generate(work(
            chunk(20L, 0, "첫 번째"),
            chunk(21L, 1, "두 번째")
        )))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_DIMENSION_MISMATCH));

        then(embeddingClient).should(never()).embedBatch(List.of("두 번째"), 1);
    }

    @Test
    @DisplayName("NaN이 포함된 Vector를 저장 Draft로 만들지 않는다")
    void generate_rejectsNonFiniteVector() {
        DocumentEmbeddingGenerator generator = generator(1);
        given(embeddingClient.embedBatch(List.of("첫 번째"), 1))
            .willReturn(response(item(0, Float.NaN, 1.0f)));

        assertThatThrownBy(() -> generator.generate(work(chunk(20L, 0, "첫 번째"))))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_VECTOR_INVALID));
    }

    @Test
    @DisplayName("빈 작업 Snapshot은 외부 서버 호출 전에 거부한다")
    void generate_rejectsEmptyWork() {
        DocumentEmbeddingGenerator generator = generator(2);
        EmbeddingWork emptyWork = new EmbeddingWork(5L, 7L, "BAAI/bge-m3", 2, List.of());

        assertThatThrownBy(() -> generator.generate(emptyWork))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT));

        then(embeddingClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Draft에서 조회한 Vector를 변경해도 보관 값은 유지된다")
    void draft_returnsVectorCopy() {
        DocumentEmbeddingDraft draft = new DocumentEmbeddingDraft(
            20L,
            0,
            CONTENT_HASH,
            new float[]{1.0f, -2.0f},
            VECTOR_HASH
        );

        float[] exposedVector = draft.vector();
        exposedVector[0] = 9.9f;

        assertThat(draft.vector()).containsExactly(1.0f, -2.0f);
    }

    private EmbeddingWork work(ChunkSnapshot... chunks) {
        return new EmbeddingWork(5L, 7L, "BAAI/bge-m3", 2, List.of(chunks));
    }

    private DocumentEmbeddingGenerator generator(int batchSize) {
        EmbeddingBatchProperties properties = new EmbeddingBatchProperties();
        properties.setBatchSize(batchSize);
        return new DocumentEmbeddingGenerator(
            embeddingClient,
            new AdaptiveEmbeddingBatchPlanner(properties)
        );
    }

    private EmbedBatchServerResponse response(EmbedBatchItemResponse... items) {
        return new EmbedBatchServerResponse("BAAI/bge-m3", List.of(items));
    }

    private EmbedBatchItemResponse item(int index, float... vector) {
        return new EmbedBatchItemResponse(index, vector);
    }

    private ChunkSnapshot chunk(Long chunkId, int chunkIndex, String text) {
        return new ChunkSnapshot(chunkId, chunkIndex, text, 1, CONTENT_HASH);
    }
}
