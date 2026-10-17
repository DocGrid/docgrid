package com.opensource.docgrid.domain.search.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.opensource.docgrid.domain.search.benchmark.PermissionRecallCorpus.RealChunk;
import com.opensource.docgrid.domain.search.benchmark.PermissionRecallCorpus.RealDocument;
import com.opensource.docgrid.domain.search.benchmark.PermissionRecallDataset.DocumentSpec;

/**
 * 권한 필터 검색 누락 시험(#545) 하네스의 DB 없는 계산 단위 테스트.
 *
 * <p>합성 Vector 수식, 결정적 확장, 허용 문서 선택, Recall·백분위만 검증한다. 측정 자체는 {@link PermissionRecallBenchmark}가 한다.
 */
@DisplayName("권한 필터 검색 누락 시험 데이터·지표 계산 단위 테스트")
class PermissionRecallDatasetTest {

    private static final int DIMENSION = 64;

    @Test
    @DisplayName("합성 Vector는 단위 길이이고 원본과의 코사인 유사도가 지정한 값과 같다")
    void mix_keepsUnitLengthAndExactSimilarity() {
        Random random = new Random(1L);
        float[] source = PermissionRecallDataset.normalize(randomVector(random));
        float[] other = PermissionRecallDataset.normalize(randomVector(random));

        float[] mixed = PermissionRecallDataset.mix(source, other, 0.83, new Random(2L));

        assertThat(PermissionRecallDataset.dot(mixed, mixed)).isCloseTo(1.0, within(1e-5));
        assertThat(PermissionRecallDataset.dot(mixed, source)).isCloseTo(0.83, within(1e-5));
    }

    @Test
    @DisplayName("섞을 Vector가 원본과 같은 방향이어도 무작위 직교 방향으로 유사도를 맞춘다")
    void mix_fallsBackWhenOtherIsParallel() {
        float[] source = PermissionRecallDataset.normalize(randomVector(new Random(3L)));

        float[] mixed = PermissionRecallDataset.mix(source, source.clone(), 0.9, new Random(4L));

        assertThat(PermissionRecallDataset.dot(mixed, source)).isCloseTo(0.9, within(1e-5));
    }

    @Test
    @DisplayName("확장은 실제 문서를 먼저 모두 넣고 목표 Chunk 수 이상이 될 때까지 같은 Seed로 같은 결과를 만든다")
    void expand_isDeterministicAndReachesTarget() {
        List<RealDocument> documents = documents(5, 3);

        PermissionRecallDataset first = PermissionRecallDataset.expand(documents, 40, 0.7, 0.9, 7L);
        PermissionRecallDataset second = PermissionRecallDataset.expand(documents, 40, 0.7, 0.9, 7L);

        assertThat(first.chunkCount()).isGreaterThanOrEqualTo(40);
        assertThat(first.documents()).isEqualTo(second.documents());
        assertThat(first.documents().subList(0, 5)).noneMatch(DocumentSpec::synthetic);
        DocumentSpec synthetic = first.documents().get(5);
        assertThat(synthetic.mixDocument()).isNotEqualTo(synthetic.sourceDocument());
        assertThat(synthetic.similarity()).isBetween(0.7, 0.9);
        assertThat(first.vector(synthetic, 1)).containsExactly(second.vector(synthetic, 1));
    }

    @Test
    @DisplayName("목표가 실제 Chunk 수 이하면 실제 문서만 쓰고 Vector도 원본 그대로다")
    void expand_usesOnlyRealDocumentsWhenTargetIsSmall() {
        List<RealDocument> documents = documents(4, 2);

        PermissionRecallDataset dataset = PermissionRecallDataset.expand(documents, 0, 0.7, 0.9, 7L);

        assertThat(dataset.documents()).hasSize(4).noneMatch(DocumentSpec::synthetic);
        assertThat(dataset.vector(dataset.documents().get(2), 1))
            .containsExactly(documents.get(2).chunks().get(1).vector());
    }

    @Test
    @DisplayName("허용 문서는 최소 1건이고 낮은 비율의 허용 문서가 높은 비율에 포함된다")
    void permittedDocumentIndexes_areNestedAndAtLeastOne() {
        List<Integer> tenPercent = PermissionRecallDataset.permittedDocumentIndexes(1_000, 0.1, 545L);
        List<Integer> onePercent = PermissionRecallDataset.permittedDocumentIndexes(1_000, 0.01, 545L);
        List<Integer> tiny = PermissionRecallDataset.permittedDocumentIndexes(100, 0.001, 545L);

        assertThat(tenPercent).hasSize(100).containsAll(onePercent);
        assertThat(onePercent).hasSize(10);
        assertThat(tiny).hasSize(1);
    }

    @Test
    @DisplayName("다른 문서 최근접 유사도는 같은 문서 Chunk를 제외하고 계산한다")
    void crossDocumentNearestSimilarities_excludesSameDocument() {
        float[] base = {1F, 0F, 0F};
        List<RealDocument> documents = List.of(
            document("a", List.of(base, base.clone())),
            document("b", List.of(new float[] {0F, 1F, 0F}))
        );

        double[] nearest = PermissionRecallDataset.crossDocumentNearestSimilarities(documents);

        assertThat(nearest).containsExactly(0.0, 0.0, 0.0);
    }

    @Test
    @DisplayName("Recall은 정답 집합 기준이고 정답이 없으면 계산하지 않는다")
    void recall_isBasedOnExpectedSet() {
        assertThat(PermissionRecallMetrics.recall(List.of(1L, 2L, 9L), List.of(1L, 2L, 3L, 4L))).isEqualTo(0.5);
        assertThat(PermissionRecallMetrics.recall(List.of(1L), List.of())).isNull();
        assertThat(PermissionRecallMetrics.percentile(new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 0.95))
            .isEqualTo(10.0);
        assertThat(PermissionRecallMetrics.mean(Arrays.asList(1.0, null, 0.0))).isEqualTo(0.5);
    }

    private static List<RealDocument> documents(int count, int chunksPerDocument) {
        Random random = new Random(11L);
        List<RealDocument> documents = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            List<float[]> vectors = new ArrayList<>();
            for (int chunk = 0; chunk < chunksPerDocument; chunk++) {
                vectors.add(randomVector(random));
            }
            documents.add(document("doc-" + index, vectors));
        }
        return documents;
    }

    private static RealDocument document(String name, List<float[]> vectors) {
        List<RealChunk> chunks = new ArrayList<>();
        for (int index = 0; index < vectors.size(); index++) {
            chunks.add(new RealChunk(index, name + " chunk " + index, 1, index * 10, index * 10 + 5, null, null,
                "hash", null, vectors.get(index)));
        }
        return new RealDocument(name + ".md", "sha", name, "MD", chunks);
    }

    private static float[] randomVector(Random random) {
        float[] vector = new float[DIMENSION];
        for (int index = 0; index < DIMENSION; index++) {
            vector[index] = (float) random.nextGaussian();
        }
        return vector;
    }
}
