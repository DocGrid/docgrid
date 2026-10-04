package com.opensource.docgrid.domain.embedding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.embedding.config.EmbeddingBatchProperties;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.ChunkSnapshot;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

/**
 * AdaptiveEmbeddingBatchPlanner의 Batch 개수·Unicode 문자·Token 예산과 입력 순서 보존만 검증한다.
 *
 * <p>외부 HTTP 호출과 Spring ConfigurationProperties Binding은 이 단위 테스트의 경계에 포함하지 않는다.
 */
@DisplayName("AdaptiveEmbeddingBatchPlanner 테스트")
class AdaptiveEmbeddingBatchPlannerTest {

    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Test
    @DisplayName("Chunk 개수 상한에서 나누고 원본 순서를 보존한다")
    void plan_splitsAtItemLimitAndPreservesOrder() {
        AdaptiveEmbeddingBatchPlanner planner = planner(2, 4_000, 900);

        List<List<ChunkSnapshot>> batches = planner.plan(List.of(
            chunk(0, "첫 번째", 10),
            chunk(1, "두 번째", 10),
            chunk(2, "세 번째", 10)
        ));

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).extracting(ChunkSnapshot::chunkIndex).containsExactly(0, 1);
        assertThat(batches.get(1)).extracting(ChunkSnapshot::chunkIndex).containsExactly(2);
    }

    @Test
    @DisplayName("Supplementary 문자를 한 Code Point로 계산해 문자 예산에서 나눈다")
    void plan_usesUnicodeCodePoints() {
        AdaptiveEmbeddingBatchPlanner planner = planner(4, 3, 900);

        List<List<ChunkSnapshot>> batches = planner.plan(List.of(
            chunk(0, "가😀", 1),
            chunk(1, "나😀", 1)
        ));

        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSize(1));
    }

    @Test
    @DisplayName("Section 경로가 붙은 Embedding 입력의 길이를 문자 예산에 반영한다")
    void plan_countsEmbeddingTextLengthAgainstCodePointBudget() {
        AdaptiveEmbeddingBatchPlanner planner = planner(4, 10, 900);

        List<List<ChunkSnapshot>> withoutPath = planner.plan(List.of(
            chunk(0, "본문", 1),
            chunk(1, "본문", 1)
        ));
        List<List<ChunkSnapshot>> withPath = planner.plan(List.of(
            new ChunkSnapshot(20L, 0, "본문", "1장 > 1절\n본문", 1, CONTENT_HASH),
            new ChunkSnapshot(21L, 1, "본문", "1장 > 1절\n본문", 1, CONTENT_HASH)
        ));

        assertThat(withoutPath).hasSize(1);
        assertThat(withPath).hasSize(2);
    }

    @Test
    @DisplayName("저장된 Token 합계가 예산을 넘기 전에 다음 Batch로 분리한다")
    void plan_splitsAtEstimatedTokenLimit() {
        AdaptiveEmbeddingBatchPlanner planner = planner(4, 4_000, 900);

        List<List<ChunkSnapshot>> batches = planner.plan(List.of(
            chunk(0, "첫 번째", 500),
            chunk(1, "두 번째", 401),
            chunk(2, "세 번째", 10)
        ));

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).extracting(ChunkSnapshot::chunkIndex).containsExactly(0);
        assertThat(batches.get(1)).extracting(ChunkSnapshot::chunkIndex).containsExactly(1, 2);
    }

    @Test
    @DisplayName("단일 Chunk가 예산보다 크면 다른 Chunk와 결합하지 않고 단독 격리한다")
    void plan_isolatesIndivisibleOversizedChunk() {
        AdaptiveEmbeddingBatchPlanner planner = planner(4, 4_000, 900);

        List<List<ChunkSnapshot>> batches = planner.plan(List.of(
            chunk(0, "큰 Chunk", 901),
            chunk(1, "다음 Chunk", 10)
        ));

        assertThat(batches).hasSize(2);
        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSize(1));
    }

    @Test
    @DisplayName("빈 Text나 음수 Token Snapshot은 외부 호출 전에 거부한다")
    void plan_rejectsInvalidChunkMetadata() {
        AdaptiveEmbeddingBatchPlanner planner = planner(4, 4_000, 900);
        ChunkSnapshot invalid = chunk(0, " ", -1);

        assertThatThrownBy(() -> planner.plan(List.of(invalid)))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DOCUMENT_EMBEDDINGS_INCONSISTENT);
    }

    private AdaptiveEmbeddingBatchPlanner planner(int batchSize, int codePoints, int tokens) {
        EmbeddingBatchProperties properties = new EmbeddingBatchProperties();
        properties.setBatchSize(batchSize);
        properties.setMaxCodePoints(codePoints);
        properties.setMaxEstimatedTokens(tokens);
        return new AdaptiveEmbeddingBatchPlanner(properties);
    }

    private ChunkSnapshot chunk(int index, String text, int tokenCount) {
        return new ChunkSnapshot(20L + index, index, text, tokenCount, CONTENT_HASH);
    }
}
