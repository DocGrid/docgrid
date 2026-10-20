package com.opensource.docgrid.domain.search.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.opensource.docgrid.domain.search.benchmark.PermissionRecallCorpus.RealChunk;
import com.opensource.docgrid.domain.search.benchmark.PermissionRecallCorpus.RealDocument;

/**
 * 실제 Chunk 묶음을 시험 규모까지 늘리는 결정적 합성 계획과 권한 허용 문서 선택.
 *
 * <p>합성 문서 하나는 실제 문서 하나(원본)의 Chunk 본문·Section 정보를 그대로 복제한다. 각 Chunk Vector는 원본 Vector와의
 * 코사인 유사도가 정확히 {@code c}가 되도록, 다른 실제 문서 Chunk Vector에서 원본에 직교하는 성분만 섞어 만든다.
 * 섞는 방향이 실제 Embedding에서 오므로 무작위 Gaussian 잡음보다 실제 분포에 가깝게 남는다. {@code c}는 문서마다
 * {@code [similarityLow, similarityHigh]}에서 고르며, 호출 측이 실제 Chunk의 "다른 문서 최근접 유사도" 분포로 정한다.
 *
 * <p>Vector는 계획만 들고 있다가 {@link #vector(DocumentSpec, int)} 호출 시 Seed로 다시 계산하므로, 수만 Chunk를
 * 메모리에 올리지 않는다. DB 적재·측정은 하지 않는다.
 */
final class PermissionRecallDataset {

    private final List<RealDocument> realDocuments;
    private final List<float[][]> unitVectors;
    private final List<DocumentSpec> documents;
    private final int chunkCount;

    /**
     * 시험 데이터의 문서 한 건.
     *
     * @param sourceDocument 본문을 복제한 실제 문서 Index
     * @param mixDocument    Vector에 방향을 섞은 다른 실제 문서 Index. 실제 문서면 -1
     * @param similarity     합성 Chunk와 원본 Chunk의 코사인 유사도. 실제 문서면 1
     * @param seed           Chunk별 섞을 Chunk를 고르는 Seed
     */
    record DocumentSpec(int index, int sourceDocument, int mixDocument, double similarity, long seed) {

        boolean synthetic() {
            return mixDocument >= 0;
        }
    }

    private PermissionRecallDataset(List<RealDocument> realDocuments, List<DocumentSpec> documents) {
        this.realDocuments = realDocuments;
        this.unitVectors = realDocuments.stream()
            .map(document -> document.chunks().stream().map(chunk -> normalize(chunk.vector())).toArray(float[][]::new))
            .toList();
        this.documents = List.copyOf(documents);
        this.chunkCount = documents.stream()
            .mapToInt(spec -> realDocuments.get(spec.sourceDocument()).chunks().size())
            .sum();
    }

    /**
     * 실제 문서를 모두 넣고, 전체 Chunk 수가 {@code targetChunks} 이상이 될 때까지 합성 문서를 더한다.
     * {@code targetChunks}가 실제 Chunk 수 이하면 실제 문서만 쓴다.
     */
    static PermissionRecallDataset expand(
        List<RealDocument> realDocuments,
        int targetChunks,
        double similarityLow,
        double similarityHigh,
        long seed
    ) {
        if (realDocuments.size() < 2) {
            throw new IllegalArgumentException("합성에는 실제 문서가 2건 이상 필요합니다.");
        }
        List<DocumentSpec> specs = new ArrayList<>();
        int total = 0;
        for (int index = 0; index < realDocuments.size(); index++) {
            specs.add(new DocumentSpec(index, index, -1, 1.0, 0L));
            total += realDocuments.get(index).chunks().size();
        }

        Random random = new Random(seed);
        while (total < targetChunks) {
            int source = random.nextInt(realDocuments.size());
            int mix = random.nextInt(realDocuments.size() - 1);
            if (mix >= source) {
                mix++;
            }
            double similarity = similarityLow + random.nextDouble() * (similarityHigh - similarityLow);
            specs.add(new DocumentSpec(specs.size(), source, mix, similarity, random.nextLong()));
            total += realDocuments.get(source).chunks().size();
        }
        return new PermissionRecallDataset(realDocuments, specs);
    }

    List<DocumentSpec> documents() {
        return documents;
    }

    int chunkCount() {
        return chunkCount;
    }

    int realChunkCount() {
        return realDocuments.stream().mapToInt(document -> document.chunks().size()).sum();
    }

    RealDocument source(DocumentSpec spec) {
        return realDocuments.get(spec.sourceDocument());
    }

    /**
     * 문서의 {@code chunkIndex}번째 Chunk Vector. 실제 문서는 서버 응답 값 그대로, 합성 문서는 매번 같은 값으로 다시 계산한다.
     */
    float[] vector(DocumentSpec spec, int chunkIndex) {
        RealChunk sourceChunk = source(spec).chunks().get(chunkIndex);
        if (!spec.synthetic()) {
            return sourceChunk.vector();
        }
        float[][] mixChunks = unitVectors.get(spec.mixDocument());
        Random random = new Random(spec.seed() * 31 + chunkIndex);
        float[] other = mixChunks[random.nextInt(mixChunks.length)];
        return mix(unitVectors.get(spec.sourceDocument())[chunkIndex], other, spec.similarity(), random);
    }

    /**
     * 단위 Vector {@code source}와의 코사인 유사도가 정확히 {@code similarity}인 단위 Vector를 만든다.
     *
     * <p>{@code other}에서 {@code source} 방향을 뺀 직교 성분을 섞는다. 두 Vector가 거의 같은 방향이라 직교 성분이 없으면
     * 무작위 방향으로 대신한다.
     */
    static float[] mix(float[] source, float[] other, double similarity, Random fallback) {
        double projection = dot(source, other);
        double[] orthogonal = new double[source.length];
        double squaredNorm = 0.0;
        for (int index = 0; index < source.length; index++) {
            orthogonal[index] = other[index] - projection * source[index];
            squaredNorm += orthogonal[index] * orthogonal[index];
        }
        if (squaredNorm < 1e-12) {
            squaredNorm = 0.0;
            for (int index = 0; index < source.length; index++) {
                orthogonal[index] = fallback.nextGaussian();
            }
            double fallbackProjection = 0.0;
            for (int index = 0; index < source.length; index++) {
                fallbackProjection += orthogonal[index] * source[index];
            }
            for (int index = 0; index < source.length; index++) {
                orthogonal[index] -= fallbackProjection * source[index];
                squaredNorm += orthogonal[index] * orthogonal[index];
            }
        }

        double orthogonalNorm = Math.sqrt(squaredNorm);
        double orthogonalWeight = Math.sqrt(1.0 - similarity * similarity);
        float[] mixed = new float[source.length];
        for (int index = 0; index < source.length; index++) {
            mixed[index] = (float) (similarity * source[index] + orthogonalWeight * orthogonal[index] / orthogonalNorm);
        }
        return mixed;
    }

    /**
     * 실제 Chunk마다 "다른 문서 Chunk 중 가장 높은 코사인 유사도"를 구해 오름차순으로 돌려준다. 합성 유사도 범위를 정하는 근거다.
     */
    static double[] crossDocumentNearestSimilarities(List<RealDocument> realDocuments) {
        List<float[]> vectors = new ArrayList<>();
        List<Integer> owners = new ArrayList<>();
        for (int documentIndex = 0; documentIndex < realDocuments.size(); documentIndex++) {
            for (RealChunk chunk : realDocuments.get(documentIndex).chunks()) {
                vectors.add(normalize(chunk.vector()));
                owners.add(documentIndex);
            }
        }
        double[] nearest = new double[vectors.size()];
        Arrays.fill(nearest, -1.0);
        for (int left = 0; left < vectors.size(); left++) {
            for (int right = left + 1; right < vectors.size(); right++) {
                if (owners.get(left).equals(owners.get(right))) {
                    continue;
                }
                double similarity = dot(vectors.get(left), vectors.get(right));
                nearest[left] = Math.max(nearest[left], similarity);
                nearest[right] = Math.max(nearest[right], similarity);
            }
        }
        Arrays.sort(nearest);
        return nearest;
    }

    /**
     * 문서 Index를 Seed로 섞은 뒤 앞에서부터 {@code ratio} 비율(최소 1건)을 허용 문서로 고른다.
     * 같은 Seed면 낮은 비율의 허용 문서가 높은 비율의 허용 문서에 항상 포함된다.
     */
    static List<Integer> permittedDocumentIndexes(int documentCount, double ratio, long seed) {
        List<Integer> shuffled = new ArrayList<>(documentCount);
        for (int index = 0; index < documentCount; index++) {
            shuffled.add(index);
        }
        Collections.shuffle(shuffled, new Random(seed));
        int count = Math.max(1, (int) Math.round(documentCount * ratio));
        return List.copyOf(shuffled.subList(0, Math.min(count, documentCount)));
    }

    static float[] normalize(float[] vector) {
        double norm = Math.sqrt(dot(vector, vector));
        float[] normalized = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            normalized[index] = (float) (vector[index] / norm);
        }
        return normalized;
    }

    static double dot(float[] left, float[] right) {
        double sum = 0.0;
        for (int index = 0; index < left.length; index++) {
            sum += (double) left[index] * right[index];
        }
        return sum;
    }
}
