package com.opensource.docgrid.domain.document.benchmark;

import static com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.summarizeTimings;

import java.io.IOException;
import java.time.Clock;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.ChunkCandidate;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.ChunkProfile;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.ChunkedCorpus;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.QualityMetrics;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.QueryCase;
import com.opensource.docgrid.domain.document.benchmark.ChunkQualityBenchmarkSupport.TimingSummary;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitBreaker;
import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitMetrics;
import com.opensource.docgrid.domain.embedding.config.EmbeddingProviderCircuitBreakerProperties;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * 실제 FixedSizeChunker와 BAAI/bge-m3를 사용해 Chunk Size·Overlap별 검색 품질과 비용을 비교한다.
 *
 * <p>Chunking 효과를 격리하기 위해 DB·HNSW 대신 메모리 내 Exact Cosine Ranking을 사용한다.
 * 실제 외부 모델을 호출하는 장시간 작업이므로 일반 테스트에서 제외하고 전용 Gradle Task로만 실행한다.
 */
@Slf4j
@Tag("chunk-quality-performance")
@DisplayName("Chunk Size·Overlap 검색 품질·비용 Benchmark")
class ChunkQualityPerformanceBenchmark {

    private static final String EXPECTED_MODEL = "BAAI/bge-m3";
    private static final int MAX_REQUEST_TEXTS = 64;
    private static final double COMPARISON_EPSILON = 1.0E-12;

    @Test
    @Timeout(value = 3_600, unit = TimeUnit.SECONDS)
    @DisplayName("8개 Profile의 실제 BGE-M3 품질과 중복·임베딩 비용을 비교한다")
    void compareChunkSizeAndOverlapQuality() throws IOException {
        BenchmarkConfiguration configuration = BenchmarkConfiguration.fromSystemProperties();
        RestClient restClient = RestClient.builder().baseUrl(configuration.serverUri().toString()).build();
        EmbeddingProviderCircuitBreakerProperties circuitProperties =
            new EmbeddingProviderCircuitBreakerProperties();
        circuitProperties.setEnabled(false);
        EmbeddingClient embeddingClient = new EmbeddingClient(
            restClient,
            restClient,
            new EmbeddingProviderCircuitBreaker(
                circuitProperties,
                Clock.systemUTC(),
                new EmbeddingProviderCircuitMetrics(new SimpleMeterRegistry())
            )
        );
        List<QueryCase> corpus = ChunkQualityBenchmarkSupport.createCorpus();
        List<ChunkProfile> profiles = ChunkQualityBenchmarkSupport.profiles();

        // 1. Health와 Warm-up을 측정 전에 끝내 Model Loading 시간을 Profile 지연에서 제외한다.
        verifyHealth(restClient);
        warmUp(embeddingClient, configuration, corpus);

        // 2. Query Vector를 한 번만 생성해 모든 Profile이 같은 검색 입력을 공유하게 한다.
        long queryEmbeddingStartedAt = System.nanoTime();
        EmbeddedVectors queryEmbeddings = embed(
            embeddingClient,
            corpus.stream().map(QueryCase::queryId).toList(),
            corpus.stream().map(QueryCase::question).toList(),
            configuration.batchSize()
        );
        double queryEmbeddingMillis = nanosToMillis(System.nanoTime() - queryEmbeddingStartedAt);

        // 3. Round마다 Profile 시작 순서를 회전해 실행 순서와 Host 열 상태 편향을 줄인다.
        Map<String, ProfileAccumulator> accumulators = new LinkedHashMap<>();
        profiles.forEach(profile -> accumulators.put(profile.profileId(), new ProfileAccumulator(profile)));
        for (int round = 0; round < configuration.rounds(); round++) {
            for (int offset = 0; offset < profiles.size(); offset++) {
                ChunkProfile profile = profiles.get((round + offset) % profiles.size());
                RoundMeasurement measurement = runProfileRound(
                    profile,
                    corpus,
                    embeddingClient,
                    configuration.batchSize(),
                    queryEmbeddings.vectors()
                );
                accumulators.get(profile.profileId()).add(measurement);
            }
        }

        // 4. 결정적 품질·비용과 반복 지연을 집계하고 비용 대비 비지배 Profile을 표시한다.
        List<ProfileResult> preliminary = profiles.stream()
            .map(profile -> accumulators.get(profile.profileId()).toResult(false))
            .toList();
        List<ProfileResult> results = preliminary.stream()
            .map(result -> result.withParetoCandidate(isParetoCandidate(result, preliminary)))
            .toList();

        BenchmarkReport report = new BenchmarkReport(
            Instant.now().toString(),
            EXPECTED_MODEL,
            sanitizedEndpoint(configuration.serverUri()),
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            System.getProperty("java.version"),
            configuration.warmUpRuns(),
            configuration.rounds(),
            configuration.batchSize(),
            MAX_REQUEST_TEXTS,
            corpus.size(),
            corpus.size(),
            ChunkQualityBenchmarkSupport.DOCUMENT_LENGTH * (long) corpus.size(),
            queryEmbeddingMillis,
            queryEmbeddings.requestCount(),
            List.copyOf(results)
        );

        writeReport(configuration.outputPath(), report);
        logSummary(report);
    }

    private RoundMeasurement runProfileRound(
        ChunkProfile profile,
        List<QueryCase> corpus,
        EmbeddingClient embeddingClient,
        int batchSize,
        Map<String, float[]> queryVectors
    ) {
        long chunkingStartedAt = System.nanoTime();
        ChunkedCorpus chunked = ChunkQualityBenchmarkSupport.chunk(corpus, profile);
        double chunkingMillis = nanosToMillis(System.nanoTime() - chunkingStartedAt);

        List<ChunkCandidate> candidates = chunked.candidates();
        long embeddingStartedAt = System.nanoTime();
        EmbeddedVectors chunkEmbeddings = embed(
            embeddingClient,
            candidates.stream().map(ChunkCandidate::candidateId).toList(),
            candidates.stream().map(ChunkCandidate::text).toList(),
            batchSize
        );
        double embeddingMillis = nanosToMillis(System.nanoTime() - embeddingStartedAt);

        long searchStartedAt = System.nanoTime();
        QualityMetrics quality = ChunkQualityBenchmarkSupport.evaluate(
            corpus,
            candidates,
            queryVectors,
            chunkEmbeddings.vectors()
        );
        double searchMillis = nanosToMillis(System.nanoTime() - searchStartedAt);

        return new RoundMeasurement(
            chunked,
            quality,
            chunkingMillis,
            embeddingMillis,
            searchMillis,
            chunkEmbeddings.requestCount()
        );
    }

    private void verifyHealth(RestClient restClient) {
        restClient.get().uri("/health").retrieve().toBodilessEntity();
    }

    private void warmUp(
        EmbeddingClient embeddingClient,
        BenchmarkConfiguration configuration,
        List<QueryCase> corpus
    ) {
        ChunkedCorpus representative = ChunkQualityBenchmarkSupport.chunk(
            corpus.subList(0, 1),
            ChunkQualityBenchmarkSupport.profiles().get(0)
        );
        List<String> texts = List.of(corpus.get(0).question(), representative.candidates().get(0).text());
        List<String> ids = List.of("warmup-query", "warmup-chunk");
        for (int run = 0; run < configuration.warmUpRuns(); run++) {
            embed(embeddingClient, ids, texts, Math.min(configuration.batchSize(), texts.size()));
        }
    }

    private EmbeddedVectors embed(
        EmbeddingClient embeddingClient,
        List<String> ids,
        List<String> texts,
        int batchSize
    ) {
        if (ids.size() != texts.size() || texts.isEmpty()) {
            throw new IllegalArgumentException("Embedding ID와 Text는 같은 개수의 비어 있지 않은 목록이어야 합니다.");
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        int requestCount = 0;

        // 외부 API의 Text 수 상한과 모델 내부 Batch Size를 분리해 큰 Corpus도 순서를 보존한다.
        for (int start = 0; start < texts.size(); start += MAX_REQUEST_TEXTS) {
            int end = Math.min(start + MAX_REQUEST_TEXTS, texts.size());
            EmbedBatchServerResponse response = embeddingClient.embedBatch(texts.subList(start, end), batchSize);
            if (!EXPECTED_MODEL.equals(response.model())) {
                throw new IllegalStateException("예상하지 않은 Embedding Model입니다: " + response.model());
            }
            for (EmbedBatchItemResponse item : response.embeddings()) {
                float[] vector = item.vector();
                ChunkQualityBenchmarkSupport.validateVector(vector);
                vectors.add(vector);
            }
            requestCount++;
        }
        return new EmbeddedVectors(ChunkQualityBenchmarkSupport.vectorMap(ids, vectors), requestCount);
    }

    private boolean isParetoCandidate(ProfileResult candidate, List<ProfileResult> results) {
        // record의 값 기반 equals는 지표가 같은 다른 Profile까지 제외하므로 참조로 자기 자신만 건너뛴다.
        return results.stream().noneMatch(other -> other != candidate && dominates(other, candidate));
    }

    private boolean dominates(ProfileResult left, ProfileResult right) {
        boolean qualityNotWorse = greaterOrEqual(
            left.quality().answerCoverageRatio(), right.quality().answerCoverageRatio()
        ) && greaterOrEqual(left.quality().hitAt1(), right.quality().hitAt1())
            && greaterOrEqual(left.quality().hitAt3(), right.quality().hitAt3())
            && greaterOrEqual(left.quality().mrrAt10(), right.quality().mrrAt10());
        boolean costNotWorse = left.chunkCodePoints() <= right.chunkCodePoints();
        boolean strictlyBetter = greater(
            left.quality().answerCoverageRatio(), right.quality().answerCoverageRatio()
        ) || greater(left.quality().hitAt1(), right.quality().hitAt1())
            || greater(left.quality().hitAt3(), right.quality().hitAt3())
            || greater(left.quality().mrrAt10(), right.quality().mrrAt10())
            || left.chunkCodePoints() < right.chunkCodePoints();
        return qualityNotWorse && costNotWorse && strictlyBetter;
    }

    private boolean greaterOrEqual(double left, double right) {
        return left + COMPARISON_EPSILON >= right;
    }

    private boolean greater(double left, double right) {
        return left > right + COMPARISON_EPSILON;
    }

    private void writeReport(Path outputPath, BenchmarkReport report) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        new ObjectMapper().findAndRegisterModules()
            .writerWithDefaultPrettyPrinter()
            .writeValue(outputPath.toFile(), report);
    }

    private void logSummary(BenchmarkReport report) {
        for (ProfileResult profile : report.profiles()) {
            log.info(
                "Chunk 품질 결과 profile={}, coverage={}, hit@3={}, mrr@10={}, chunks={}, duplicateRatio={}, "
                    + "embeddingP95Ms={}, pareto={}",
                profile.profileId(),
                format(profile.quality().answerCoverageRatio()),
                format(profile.quality().hitAt3()),
                format(profile.quality().mrrAt10()),
                profile.chunkCount(),
                format(profile.duplicateRatio()),
                format(profile.embeddingTiming().p95Millis()),
                profile.paretoCandidate()
            );
        }
    }

    private String sanitizedEndpoint(URI serverUri) {
        int port = serverUri.getPort();
        return serverUri.getScheme() + "://" + serverUri.getHost() + (port < 0 ? "" : ":" + port);
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * 외부 Benchmark 입력을 System Property와 환경 변수에서 검증해 읽는다.
     */
    record BenchmarkConfiguration(
        URI serverUri,
        int warmUpRuns,
        int rounds,
        int batchSize,
        Path outputPath
    ) {

        BenchmarkConfiguration {
            if (serverUri == null || serverUri.getScheme() == null || serverUri.getHost() == null) {
                throw new IllegalArgumentException("Embedding Server URL은 절대 HTTP URL이어야 합니다.");
            }
            if (!"http".equals(serverUri.getScheme()) && !"https".equals(serverUri.getScheme())) {
                throw new IllegalArgumentException("Embedding Server URL은 HTTP 또는 HTTPS여야 합니다.");
            }
            if (serverUri.getUserInfo() != null) {
                throw new IllegalArgumentException("Embedding Server URL에 인증 정보를 포함할 수 없습니다.");
            }
            if (warmUpRuns < 1 || rounds < 1) {
                throw new IllegalArgumentException("Warm-up과 측정 Round는 각각 1 이상이어야 합니다.");
            }
            if (batchSize < 1 || batchSize > MAX_REQUEST_TEXTS) {
                throw new IllegalArgumentException("Batch Size는 1 이상 64 이하여야 합니다.");
            }
            if (outputPath == null) {
                throw new IllegalArgumentException("Benchmark 출력 경로가 필요합니다.");
            }
        }

        static BenchmarkConfiguration fromSystemProperties() {
            String configuredUrl = System.getProperty("chunk.quality.performance.server-url");
            if (configuredUrl == null || configuredUrl.isBlank()) {
                configuredUrl = System.getenv().getOrDefault("EMBEDDING_SERVER_URL", "http://localhost:8000");
            }
            return new BenchmarkConfiguration(
                URI.create(configuredUrl),
                integerProperty("chunk.quality.performance.warm-up-runs", 1),
                integerProperty("chunk.quality.performance.rounds", 2),
                integerProperty("chunk.quality.performance.batch-size", 32),
                Path.of(System.getProperty(
                    "chunk.quality.performance.output",
                    "build/reports/chunk-quality/chunk-quality-latest.json"
                ))
            );
        }

        private static int integerProperty(String name, int defaultValue) {
            try {
                return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("정수 System Property가 필요합니다: " + name, exception);
            }
        }

    }

    /**
     * 한 Embedding 단계에서 ID별 Vector와 실제 HTTP 요청 수를 보관한다.
     */
    private record EmbeddedVectors(Map<String, float[]> vectors, int requestCount) {
    }

    /**
     * 한 Profile의 한 Round에서 수집한 결정적 품질·비용과 구간별 지연이다.
     */
    private record RoundMeasurement(
        ChunkedCorpus chunked,
        QualityMetrics quality,
        double chunkingMillis,
        double embeddingMillis,
        double searchMillis,
        int embeddingRequestCount
    ) {
    }

    /**
     * 한 Profile의 반복 측정이 같은 품질·비용 계약을 지키는지 검증하고 통계를 집계한다.
     */
    private static final class ProfileAccumulator {

        private final ChunkProfile profile;
        private final List<RoundMeasurement> rounds = new ArrayList<>();

        private ProfileAccumulator(ChunkProfile profile) {
            this.profile = profile;
        }

        private void add(RoundMeasurement measurement) {
            if (!rounds.isEmpty()) {
                RoundMeasurement baseline = rounds.get(0);
                if (!sameDeterministicResult(baseline, measurement)) {
                    throw new IllegalStateException("Profile 반복 결과가 결정적이지 않습니다: " + profile.profileId());
                }
            }
            rounds.add(measurement);
        }

        private boolean sameDeterministicResult(RoundMeasurement left, RoundMeasurement right) {
            return left.chunked().originalCodePoints() == right.chunked().originalCodePoints()
                && left.chunked().chunkCodePoints() == right.chunked().chunkCodePoints()
                && left.chunked().duplicateCodePoints() == right.chunked().duplicateCodePoints()
                && left.chunked().candidates().size() == right.chunked().candidates().size()
                && sameQuality(left.quality(), right.quality());
        }

        private boolean sameQuality(QualityMetrics left, QualityMetrics right) {
            return Double.compare(left.answerCoverageRatio(), right.answerCoverageRatio()) == 0
                && Double.compare(left.hitAt1(), right.hitAt1()) == 0
                && Double.compare(left.hitAt3(), right.hitAt3()) == 0
                && Double.compare(left.mrrAt10(), right.mrrAt10()) == 0
                && left.queries().stream().map(result -> result.firstRelevantRank()).toList()
                    .equals(right.queries().stream().map(result -> result.firstRelevantRank()).toList());
        }

        private ProfileResult toResult(boolean paretoCandidate) {
            if (rounds.isEmpty()) {
                throw new IllegalStateException("Profile 측정 결과가 없습니다: " + profile.profileId());
            }
            RoundMeasurement baseline = rounds.get(0);
            return new ProfileResult(
                profile.profileId(),
                profile.chunkSize(),
                profile.overlap(),
                baseline.chunked().candidates().size(),
                baseline.chunked().originalCodePoints(),
                baseline.chunked().chunkCodePoints(),
                baseline.chunked().duplicateCodePoints(),
                baseline.chunked().duplicateRatio(),
                baseline.quality(),
                summarizeTimings(rounds.stream().map(RoundMeasurement::chunkingMillis).toList()),
                summarizeTimings(rounds.stream().map(RoundMeasurement::embeddingMillis).toList()),
                summarizeTimings(rounds.stream().map(RoundMeasurement::searchMillis).toList()),
                rounds.stream().mapToInt(RoundMeasurement::embeddingRequestCount).sum(),
                paretoCandidate,
                rounds.stream().map(RoundResult::from).toList()
            );
        }
    }

    /**
     * 외부 결과에 노출하는 한 Round의 지연과 요청 수다.
     */
    record RoundResult(
        double chunkingMillis,
        double embeddingMillis,
        double searchMillis,
        int embeddingRequestCount
    ) {

        private static RoundResult from(RoundMeasurement measurement) {
            return new RoundResult(
                measurement.chunkingMillis(),
                measurement.embeddingMillis(),
                measurement.searchMillis(),
                measurement.embeddingRequestCount()
            );
        }
    }

    /**
     * 한 Chunk Profile의 품질, 결정적 비용, 반복 지연과 Pareto 여부를 결합한다.
     */
    record ProfileResult(
        String profileId,
        int chunkSize,
        int overlap,
        int chunkCount,
        long originalCodePoints,
        long chunkCodePoints,
        long duplicateCodePoints,
        double duplicateRatio,
        QualityMetrics quality,
        TimingSummary chunkingTiming,
        TimingSummary embeddingTiming,
        TimingSummary searchTiming,
        int embeddingRequestCount,
        boolean paretoCandidate,
        List<RoundResult> rounds
    ) {

        ProfileResult {
            rounds = List.copyOf(rounds);
        }

        private ProfileResult withParetoCandidate(boolean value) {
            return new ProfileResult(
                profileId,
                chunkSize,
                overlap,
                chunkCount,
                originalCodePoints,
                chunkCodePoints,
                duplicateCodePoints,
                duplicateRatio,
                quality,
                chunkingTiming,
                embeddingTiming,
                searchTiming,
                embeddingRequestCount,
                value,
                rounds
            );
        }
    }

    /**
     * 재현 환경과 모든 Profile 결과를 담는 Chunk 품질 Benchmark JSON 계약이다.
     */
    record BenchmarkReport(
        String generatedAt,
        String model,
        String embeddingEndpoint,
        String osName,
        String osArchitecture,
        String javaVersion,
        int warmUpRuns,
        int measuredRounds,
        int modelBatchSize,
        int maxTextsPerRequest,
        int documentCount,
        int queryCount,
        long originalCodePoints,
        double queryEmbeddingMillis,
        int queryEmbeddingRequestCount,
        List<ProfileResult> profiles
    ) {

        BenchmarkReport {
            profiles = List.copyOf(profiles);
        }
    }
}
