package com.opensource.docgrid.domain.document.benchmark;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.opensource.docgrid.domain.document.benchmark.SectionPathBenchmarkCorpus.BenchmarkDocument;
import com.opensource.docgrid.domain.document.benchmark.SectionPathBenchmarkCorpus.QueryCase;
import com.opensource.docgrid.domain.document.config.DocumentChunkingProperties;
import com.opensource.docgrid.domain.document.service.DocumentChunkDraft;
import com.opensource.docgrid.domain.document.service.DocxDocumentParser;
import com.opensource.docgrid.domain.document.service.FixedSizeChunker;
import com.opensource.docgrid.domain.document.service.MarkdownDocumentParser;
import com.opensource.docgrid.domain.document.service.ParsedDocument;
import com.opensource.docgrid.domain.document.service.SectionPath;
import com.opensource.docgrid.domain.document.service.TextDocumentParser;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitBreaker;
import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitMetrics;
import com.opensource.docgrid.domain.embedding.config.EmbeddingProviderCircuitBreakerProperties;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영 파서·청커와 실제 BAAI/bge-m3로 Embedding 입력에 Section 경로를 붙였을 때의 검색 품질 변화를 측정한다.
 *
 * <p>같은 Chunk 집합을 두 방식으로 임베딩해 비교한다. 하나는 본문만(경로 도입 전), 다른 하나는 "경로 + 본문"이다.
 * 파서와 청커는 제품 코드를 그대로 쓰므로 이전 커밋으로 되돌리지 않고도 기준선을 재현할 수 있다.
 * DB·ANN 없이 메모리 내 Exact Cosine으로 순위를 계산하며, 실제 모델을 호출하므로 일반 테스트에서 제외한다.
 */
@Slf4j
@Tag("section-path-quality")
@DisplayName("Section 경로 Embedding 입력 검색 품질 Benchmark")
class SectionPathQualityBenchmark {

    private static final String EXPECTED_MODEL = "BAAI/bge-m3";
    private static final int MAX_REQUEST_TEXTS = 64;
    private static final int TOP_K = 10;

    @Test
    @Timeout(value = 1_800, unit = TimeUnit.SECONDS)
    @DisplayName("본문만 임베딩한 경우와 경로 + 본문을 임베딩한 경우의 Hit@K·MRR을 DOCX·Markdown에서 비교한다")
    void compareBodyOnlyAndWithSectionPath() throws IOException {
        URI serverUri = URI.create(System.getProperty(
            "section.path.quality.server-url",
            System.getenv().getOrDefault("EMBEDDING_SERVER_URL", "http://localhost:8000")
        ));
        RestClient restClient = RestClient.builder().baseUrl(serverUri.toString()).build();
        EmbeddingProviderCircuitBreakerProperties circuitProperties = new EmbeddingProviderCircuitBreakerProperties();
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
        restClient.get().uri("/health").retrieve().toBodilessEntity();

        List<QueryCase> queries = SectionPathBenchmarkCorpus.queries();
        Map<String, float[]> queryVectors = embed(
            embeddingClient,
            queries.stream().map(QueryCase::queryId).toList(),
            queries.stream().map(QueryCase::question).toList()
        );

        List<FormatResult> results = new ArrayList<>();
        for (DocumentFormat format : DocumentFormat.values()) {
            List<Candidate> allCandidates = chunkCorpus(format);
            for (Scenario scenario : Scenario.values()) {
                // 대조 시나리오는 문서 한 건만 남겨 "서비스 구분"이 필요 없는 조건에서 경로가 해가 되는지 본다.
                String onlyDocumentId = scenario == Scenario.SINGLE_DOCUMENT_CONTROL ? "doc1" : null;
                List<Candidate> candidates = allCandidates.stream()
                    .filter(candidate -> onlyDocumentId == null || candidate.documentId().equals(onlyDocumentId))
                    .toList();
                List<QueryCase> scenarioQueries = queries.stream()
                    .filter(query -> onlyDocumentId == null || query.documentId().equals(onlyDocumentId))
                    .toList();
                QualityMetrics bodyOnly = evaluate(
                    embeddingClient, scenarioQueries, queryVectors, candidates, "body", Candidate::bodyOnlyInput
                );
                QualityMetrics withPath = evaluate(
                    embeddingClient, scenarioQueries, queryVectors, candidates, "path", Candidate::withPathInput
                );
                results.add(new FormatResult(
                    format.name(),
                    scenario.name(),
                    scenarioQueries.size(),
                    candidates.size(),
                    bodyOnly,
                    withPath
                ));
            }
        }

        BenchmarkReport report = new BenchmarkReport(
            Instant.now().toString(),
            EXPECTED_MODEL,
            queries.size(),
            List.copyOf(results)
        );
        writeReport(Path.of(System.getProperty(
            "section.path.quality.output",
            "build/reports/section-path/section-path-latest.json"
        )), report);
        for (FormatResult result : results) {
            log.info(
                "Section 경로 품질 결과 format={}, scenario={}, queries={}, chunks={}, "
                    + "bodyOnly[hit@1={}, hit@3={}, mrr@10={}], withPath[hit@1={}, hit@3={}, mrr@10={}]",
                result.format(), result.scenario(), result.queryCount(), result.chunkCount(),
                format(result.bodyOnly().hitAt1()), format(result.bodyOnly().hitAt3()),
                format(result.bodyOnly().mrrAt10()),
                format(result.withPath().hitAt1()), format(result.withPath().hitAt3()),
                format(result.withPath().mrrAt10())
            );
        }
    }

    /** 운영 파서와 청커로 모든 문서를 Chunk로 만들고 두 가지 Embedding 입력을 함께 보관한다. */
    private List<Candidate> chunkCorpus(DocumentFormat format) {
        FixedSizeChunker chunker = new FixedSizeChunker(new DocumentChunkingProperties());
        DocxDocumentParser docxParser = new DocxDocumentParser();
        MarkdownDocumentParser markdownParser = new MarkdownDocumentParser(new TextDocumentParser());
        List<Candidate> candidates = new ArrayList<>();
        for (BenchmarkDocument document : SectionPathBenchmarkCorpus.documents()) {
            ParsedDocument parsed = format == DocumentFormat.DOCX
                ? docxParser.parseDocument(SectionPathBenchmarkCorpus.toDocx(document))
                : markdownParser.parseDocument(SectionPathBenchmarkCorpus.toMarkdown(document));
            for (DocumentChunkDraft draft : chunker.chunk(parsed)) {
                candidates.add(new Candidate(
                    document.documentId() + ":" + draft.chunkIndex(),
                    document.documentId(),
                    draft.chunkText(),
                    draft.chunkText(),
                    SectionPath.embeddingInput(draft.metadataJson(), draft.chunkText())
                ));
            }
        }
        return List.copyOf(candidates);
    }

    private QualityMetrics evaluate(
        EmbeddingClient embeddingClient,
        List<QueryCase> queries,
        Map<String, float[]> queryVectors,
        List<Candidate> candidates,
        String modeLabel,
        java.util.function.Function<Candidate, String> inputSelector
    ) {
        Map<String, float[]> chunkVectors = embed(
            embeddingClient,
            candidates.stream().map(candidate -> modeLabel + ":" + candidate.candidateId()).toList(),
            candidates.stream().map(inputSelector).toList()
        );

        int hitAt1 = 0;
        int hitAt3 = 0;
        double reciprocalRankSum = 0.0;
        for (QueryCase query : queries) {
            float[] queryVector = queryVectors.get(query.queryId());
            List<Candidate> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble((Candidate candidate) -> -ChunkQualityBenchmarkSupport.cosineSimilarity(
                    queryVector, chunkVectors.get(modeLabel + ":" + candidate.candidateId())))
                    .thenComparing(Candidate::candidateId))
                .toList();
            int firstRelevantRank = 0;
            for (int index = 0; index < ranked.size(); index++) {
                Candidate candidate = ranked.get(index);
                if (candidate.documentId().equals(query.documentId()) && candidate.chunkText().contains(query.evidence())) {
                    firstRelevantRank = index + 1;
                    break;
                }
            }
            if (firstRelevantRank == 1) {
                hitAt1++;
            }
            if (firstRelevantRank >= 1 && firstRelevantRank <= 3) {
                hitAt3++;
            }
            if (firstRelevantRank >= 1 && firstRelevantRank <= TOP_K) {
                reciprocalRankSum += 1.0 / firstRelevantRank;
            }
        }
        int queryCount = queries.size();
        return new QualityMetrics(
            (double) hitAt1 / queryCount,
            (double) hitAt3 / queryCount,
            reciprocalRankSum / queryCount
        );
    }

    private Map<String, float[]> embed(EmbeddingClient embeddingClient, List<String> ids, List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_REQUEST_TEXTS) {
            int end = Math.min(start + MAX_REQUEST_TEXTS, texts.size());
            EmbedBatchServerResponse response = embeddingClient.embedBatch(texts.subList(start, end), 8);
            if (!EXPECTED_MODEL.equals(response.model())) {
                throw new IllegalStateException("예상하지 않은 Embedding Model입니다: " + response.model());
            }
            for (EmbedBatchItemResponse item : response.embeddings()) {
                ChunkQualityBenchmarkSupport.validateVector(item.vector());
                vectors.add(item.vector());
            }
        }
        return ChunkQualityBenchmarkSupport.vectorMap(ids, vectors);
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

    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private enum DocumentFormat {
        DOCX,
        MARKDOWN
    }

    /** MULTI_DOCUMENT는 같은 서식 문서 4건에서 서비스 구분이 필요한 조건, 대조는 문서 1건만 쓰는 조건이다. */
    private enum Scenario {
        MULTI_DOCUMENT,
        SINGLE_DOCUMENT_CONTROL
    }

    private record Candidate(
        String candidateId,
        String documentId,
        String chunkText,
        String bodyOnlyInput,
        String withPathInput
    ) {
    }

    record QualityMetrics(double hitAt1, double hitAt3, double mrrAt10) {
    }

    record FormatResult(
        String format,
        String scenario,
        int queryCount,
        int chunkCount,
        QualityMetrics bodyOnly,
        QualityMetrics withPath
    ) {
    }

    record BenchmarkReport(String measuredAt, String model, int totalQueryCount, List<FormatResult> results) {
    }
}
