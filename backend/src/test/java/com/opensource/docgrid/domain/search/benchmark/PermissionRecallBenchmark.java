package com.opensource.docgrid.domain.search.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opensource.docgrid.domain.document.service.DocumentParserRegistry;
import com.opensource.docgrid.domain.document.service.FixedSizeChunker;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.service.AdaptiveEmbeddingBatchPlanner;
import com.opensource.docgrid.domain.embedding.service.EmbeddingVectorSupport;
import com.opensource.docgrid.domain.search.benchmark.PermissionRecallCorpus.RealChunk;
import com.opensource.docgrid.domain.search.benchmark.PermissionRecallDataset.DocumentSpec;
import com.opensource.docgrid.domain.search.config.HybridSearchProperties;
import com.opensource.docgrid.domain.search.config.VectorSearchProperties;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.repository.VectorSearchRepository;
import com.opensource.docgrid.domain.search.repository.VectorSearchRow;
import com.opensource.docgrid.domain.search.service.query.HybridSearchQueryService;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

/**
 * 권한 필터에 따른 벡터 검색 누락 재현과 인덱스 방식 비교 시험(#545).
 *
 * <p>운영 {@link VectorSearchQueryService}·{@link HybridSearchQueryService}를 그대로 호출하고, 그 아래 Repository만
 * 반환 행을 기록하는 Proxy로 감싸 "SQL이 돌려준 후보 수"와 "앱 필터 뒤 최종 건수"를 따로 잰다. 방식은 서비스 호출과 같은
 * Transaction 안에서 바꾼다. Exact는 HNSW Index를 임시로 지운 뒤 Rollback하고, 나머지는 {@code SET LOCAL}로
 * Plan Cache 모드·{@code hnsw.*}·(HNSW 강제 방식만) {@code enable_sort}를 바꾼다. 방식 목록은 {@link Method}에 있다.
 * Exact 결과를 정답으로 Recall을 계산한다. 운영 코드·설정·마이그레이션은 바꾸지 않는다.
 *
 * <p>{@code vector.search.performance.permission.phase} 시스템 속성이 있을 때만 실행한다. {@code prepare}는 실제 문서와
 * 질문 Embedding 캐시만 만들고, {@code measure}는 캐시로 데이터를 적재해 측정한다. 격리 스키마
 * {@value #TEST_SCHEMA}만 쓰며, 접속 대상 DB는 {@code DB_HOST}·{@code DB_PORT} 등 환경변수로 반드시 격리 DB를 지정한다.
 */
@Slf4j
@Tag("integration")
@Tag("vector-search-performance")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("권한 필터 × 벡터 검색 누락·인덱스 방식 비교 시험 (#545)")
class PermissionRecallBenchmark {

    static final String PROPERTY_PREFIX = "vector.search.performance.permission.";
    private static final String TEST_SCHEMA = "docgrid_permission_recall_545";
    private static final int VECTOR_DIMENSION = 1024;
    private static final String FALLBACK_HNSW_INDEX = "embeddings_vector_idx";
    private static final String TOP_K_SQL = topKSql();

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private VectorSearchRepository vectorSearchRepository;
    @Autowired private VectorSearchProperties vectorSearchProperties;
    @Autowired private HybridSearchProperties hybridSearchProperties;
    @Autowired private DocumentParserRegistry documentParserRegistry;
    @Autowired private FixedSizeChunker fixedSizeChunker;
    @Autowired private AdaptiveEmbeddingBatchPlanner adaptiveEmbeddingBatchPlanner;
    @Autowired private EmbeddingClient embeddingClient;

    /** 진행 중인 방식 Transaction의 방식. Repository Proxy가 HNSW 강제 여부를 판단할 때 쓴다(단일 Thread 실행). */
    private Method currentMethod;

    @DynamicPropertySource
    static void configureEnvironment(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-permission-recall-benchmark-secret-key-2026");
        registry.add(
            "spring.datasource.hikari.data-source-properties.ApplicationName",
            () -> "docgrid-permission-recall-benchmark"
        );
    }

    @AfterAll
    void dropIsolatedSchema() {
        if (Boolean.parseBoolean(System.getProperty(PROPERTY_PREFIX + "drop-schema", "true"))) {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
        }
    }

    @Test
    @Timeout(7_200)
    @EnabledIfSystemProperty(named = PROPERTY_PREFIX + "phase", matches = "prepare")
    @DisplayName("실제 문서와 질문을 운영 처리 경로로 Embedding해 캐시한다")
    void prepareEmbeddingCaches() throws Exception {
        Configuration configuration = Configuration.fromSystemProperties();
        PermissionRecallCorpus corpus = loadCorpus(configuration);
        log.info("[PERMISSION-RECALL] corpus documents={} chunks={} skipped={} embeddingSeconds={}",
            corpus.documents().size(), corpus.chunkCount(), corpus.skippedSources(), corpus.embeddingSeconds());
        if (Files.exists(configuration.questionsPath())) {
            Map<String, float[]> vectors = loadQuestionVectors(configuration, loadQuestions(configuration));
            log.info("[PERMISSION-RECALL] question vectors={}", vectors.size());
        }
        assertThat(corpus.chunkCount()).isPositive();
    }

    @Test
    @Timeout(14_400)
    @EnabledIfSystemProperty(named = PROPERTY_PREFIX + "phase", matches = "measure")
    @DisplayName("허용 문서 비율 × topK × 방식별로 SQL 후보 수·최종 건수·Recall·지연·실행 계획을 잰다")
    void measurePermissionRecall() throws Exception {
        Configuration configuration = Configuration.fromSystemProperties();
        assertThat(configuration.methods().get(0)).isEqualTo(Method.EXACT);

        PermissionRecallCorpus corpus = loadCorpus(configuration);
        List<Question> questions = loadQuestions(configuration);
        Map<String, float[]> questionVectors = loadQuestionVectors(configuration, questions);
        Long modelId = jdbcTemplate.queryForObject(
            "SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE", Long.class);

        HnswIndex hnswIndex = currentHnswIndex();
        Map<String, String> settings = verifySessionSettings();

        Recorder recorder = new Recorder();
        VectorSearchRepository countingRepository = countingRepository(recorder);
        VectorSearchQueryService vectorService =
            new VectorSearchQueryService(countingRepository, vectorSearchProperties);
        HybridSearchQueryService hybridService =
            new HybridSearchQueryService(countingRepository, vectorSearchProperties, hybridSearchProperties);

        // 1. 합성 Chunk와 원본의 유사도는 실제 Chunk의 "다른 문서 최근접 유사도" 분포 P50~P90 안에서 고른다.
        double[] nearest = PermissionRecallDataset.crossDocumentNearestSimilarities(corpus.documents());
        Distribution nearestSimilarity = Distribution.of(nearest);
        double similarityLow = nearestSimilarity.p50();
        double similarityHigh = nearestSimilarity.p90();

        List<ScaleResult> scales = new ArrayList<>();
        List<MeasurementRow> rows = new ArrayList<>();
        List<PlanSample> planSamples = new ArrayList<>();
        for (String scale : configuration.scales()) {
            int targetChunks = "real".equals(scale) ? 0 : Integer.parseInt(scale);
            PermissionRecallDataset dataset = PermissionRecallDataset.expand(
                corpus.documents(), targetChunks, similarityLow, similarityHigh, configuration.seed());
            LoadResult load = loadDataset(dataset, modelId, hnswIndex);
            log.info("[PERMISSION-RECALL] scale={} documents={} chunks={} indexBuildSeconds={}",
                scale, dataset.documents().size(), dataset.chunkCount(), load.indexBuildSeconds());

            List<RatioInfo> ratioInfos = new ArrayList<>();
            for (double ratio : configuration.ratios()) {
                List<Long> permittedIds = PermissionRecallDataset
                    .permittedDocumentIndexes(dataset.documents().size(), ratio, configuration.seed())
                    .stream().map(index -> index + 1L).sorted().toList();
                int permittedChunks = permittedIds.stream()
                    .mapToInt(id -> dataset.source(dataset.documents().get((int) (id - 1))).chunks().size())
                    .sum();
                ratioInfos.add(new RatioInfo(ratio, permittedIds.size(), permittedChunks));

                for (int topK : configuration.topKs()) {
                    Map<String, QuestionOutcome> exact = new LinkedHashMap<>();
                    for (Method method : configuration.methods()) {
                        for (int questionIndex = 0; questionIndex < questions.size(); questionIndex++) {
                            Question question = questions.get(questionIndex);
                            QuestionOutcome outcome = measureQuestion(
                                configuration, method, hnswIndex, question, questionVectors.get(question.text()),
                                modelId, permittedIds, topK, vectorService, hybridService, recorder);
                            if (method == Method.EXACT) {
                                exact.put(question.id(), outcome);
                            }
                            if (questionIndex == 0) {
                                planSamples.add(new PlanSample(scale, ratio, topK, method.label(), question.id(),
                                    outcome.rawPlan()));
                            }
                            rows.add(MeasurementRow.of(scale, ratio, permittedIds.size(), permittedChunks, topK,
                                topK * vectorSearchProperties.getCandidatePoolMultiplier(), method, question,
                                outcome, exact.get(question.id())));
                        }
                        log.info("[PERMISSION-RECALL] scale={} ratio={} topK={} method={} done",
                            scale, ratio, topK, method.label());
                    }
                }
            }
            scales.add(new ScaleResult(
                scale, dataset.documents().size(), corpus.documents().size(),
                dataset.documents().size() - corpus.documents().size(), dataset.chunkCount(),
                dataset.realChunkCount(), 1.0 - (double) dataset.realChunkCount() / dataset.chunkCount(),
                similarityLow, similarityHigh, load.loadSeconds(), load.indexBuildSeconds(),
                load.tableBytes(), load.indexBytes(), load.totalBytes(), List.copyOf(ratioInfos)));
        }

        Report report = new Report(
            Instant.now().toString(),
            jdbcTemplate.queryForObject("SHOW server_version", String.class),
            jdbcTemplate.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class),
            settings,
            System.getProperty("os.arch"),
            System.getProperty("java.version"),
            jdbcDriverVersion(),
            corpus.embeddingModel(),
            corpus.documents().size(),
            corpus.chunkCount(),
            corpus.skippedSources(),
            corpus.embeddingSeconds(),
            nearestSimilarity,
            new SearchSettings(
                vectorSearchProperties.getMinSimilarity().doubleValue(),
                vectorSearchProperties.getMaxChunksPerDocument(),
                vectorSearchProperties.getCandidatePoolMultiplier(),
                hybridSearchProperties.isEnabled(),
                hybridSearchProperties.getVectorMinSimilarity().doubleValue(),
                hybridSearchProperties.getMinCoverage().doubleValue(),
                hybridSearchProperties.getCoverageWeight().doubleValue(),
                hybridSearchProperties.getLexicalCandidatePoolMultiplier()),
            hnswIndex.definition(),
            configuration,
            List.copyOf(scales),
            CombinationSummary.summarize(rows),
            List.copyOf(rows)
        );
        writeJson(configuration.outputPath(), report);
        writeJson(configuration.planOutputPath(), planSamples);
        assertThat(rows).isNotEmpty();
    }

    private QuestionOutcome measureQuestion(
        Configuration configuration,
        Method method,
        HnswIndex hnswIndex,
        Question question,
        float[] vector,
        Long modelId,
        List<Long> permittedIds,
        int topK,
        VectorSearchQueryService vectorService,
        HybridSearchQueryService hybridService,
        Recorder recorder
    ) throws IOException {
        int candidateLimit = topK * vectorSearchProperties.getCandidatePoolMultiplier();

        // 1. 같은 방식 설정으로 운영 findTopK SQL의 실행 계획을 한 번 남긴다(EXPLAIN ANALYZE는 사용자 지연과 별도).
        String rawPlan = inMethodTransaction(method, hnswIndex, () -> {
            setSortForTopK(method, false);
            return namedParameterJdbcTemplate.queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + TOP_K_SQL,
                new MapSqlParameterSource()
                    .addValue("queryVector", vectorString(vector))
                    .addValue("modelId", modelId)
                    .addValue("permittedIds", permittedIds)
                    .addValue("topK", candidateLimit),
                String.class);
        });
        PlanSummary plan = PlanSummary.parse(objectMapper.readTree(rawPlan), hnswIndex.name());

        // 2. 예열 후 벡터 단독·하이브리드 경로를 같은 방식 설정으로 번갈아 호출하고, 첫 측정 결과를 기준으로 쓴다.
        //    Generic Plan 방식은 한 번이 수 초라 예열·반복을 별도 설정으로 줄인다.
        boolean generic = method.planCacheMode() == PlanCacheMode.GENERIC;
        int warmUpRuns = generic ? configuration.genericWarmUpRuns() : configuration.warmUpRuns();
        int measuredRuns = generic ? configuration.genericMeasuredRuns() : configuration.measuredRuns();
        for (int run = 0; run < warmUpRuns; run++) {
            inMethodTransaction(method, hnswIndex, () -> vectorService.search(vector, modelId, permittedIds, topK));
            inMethodTransaction(method, hnswIndex,
                () -> hybridService.search(vector, question.text(), modelId, permittedIds, topK));
        }

        List<Double> vectorMillis = new ArrayList<>();
        List<Double> hybridMillis = new ArrayList<>();
        List<Long> sqlChunkIds = null;
        List<Long> vectorFinalIds = null;
        List<Long> hybridFinalIds = null;
        int hybridLexicalRows = 0;
        boolean stable = true;
        for (int run = 0; run < measuredRuns; run++) {
            recorder.reset();
            Timed<List<VectorSearchCandidate>> vectorResult = inMethodTransaction(method, hnswIndex,
                () -> timed(() -> vectorService.search(vector, modelId, permittedIds, topK)));
            List<Long> runSqlIds = recorder.topKChunkIds();
            List<Long> runVectorIds = chunkIds(vectorResult.value());

            recorder.reset();
            Timed<List<VectorSearchCandidate>> hybridResult = inMethodTransaction(method, hnswIndex,
                () -> timed(() -> hybridService.search(vector, question.text(), modelId, permittedIds, topK)));
            List<Long> runHybridIds = chunkIds(hybridResult.value());

            vectorMillis.add(vectorResult.millis());
            hybridMillis.add(hybridResult.millis());
            if (run == 0) {
                sqlChunkIds = runSqlIds;
                vectorFinalIds = runVectorIds;
                hybridFinalIds = runHybridIds;
                hybridLexicalRows = recorder.lexicalRows();
            } else {
                stable &= sqlChunkIds.equals(runSqlIds) && vectorFinalIds.equals(runVectorIds)
                    && hybridFinalIds.equals(runHybridIds);
            }
        }
        return new QuestionOutcome(sqlChunkIds, vectorFinalIds, hybridFinalIds, hybridLexicalRows,
            List.copyOf(vectorMillis), List.copyOf(hybridMillis), stable, plan, rawPlan);
    }

    /**
     * 서비스 호출과 같은 Transaction에서 방식을 바꾸고 항상 Rollback한다. Exact의 Index 삭제도 Rollback으로 되돌린다.
     *
     * <p>Plan Cache 모드는 매번 명시한다. 기본값({@code auto})이면 같은 SQL을 5번 넘게 실행한 Connection에서 Generic Plan으로
     * 바뀔 수 있어 실행 순서에 따라 지연이 달라지기 때문이다. HNSW 강제 방식의 {@code enable_sort=off}는 단어 갈래 조회에
     * 영향을 주지 않도록 Repository Proxy가 findTopK 직전에만 켜고 직후에 되돌린다.
     */
    private <T> T inMethodTransaction(Method method, HnswIndex hnswIndex, Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("SET LOCAL plan_cache_mode = " + method.planCacheMode().setting());
            if (method == Method.EXACT) {
                jdbcTemplate.execute("DROP INDEX " + TEST_SCHEMA + "." + hnswIndex.name());
            } else {
                jdbcTemplate.execute("SET LOCAL hnsw.ef_search = " + method.efSearch());
                jdbcTemplate.execute("SET LOCAL hnsw.iterative_scan = " + method.iterativeScan());
            }
            currentMethod = method;
            try {
                return work.get();
            } finally {
                currentMethod = null;
            }
        });
    }

    /**
     * HNSW 강제 방식이면 findTopK 실행 동안만 정렬 계획을 밀어낸다. {@code enabled=false}가 강제 시작, {@code true}가 원복이다.
     */
    private void setSortForTopK(Method method, boolean enabled) {
        if (method != null && method.forceHnsw()) {
            jdbcTemplate.execute("SET LOCAL enable_sort = " + (enabled ? "on" : "off"));
        }
    }

    /**
     * JdbcTemplate의 {@code SET LOCAL}이 JPA Native Query와 같은 Connection·Transaction에 적용되는지 먼저 확인하고,
     * 측정에 영향을 주는 PostgreSQL·pgvector 설정값을 기록한다.
     */
    private Map<String, String> verifySessionSettings() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            status.setRollbackOnly();
            jdbcTemplate.queryForObject("SELECT CAST('[1]' AS vector)::text", String.class);
            jdbcTemplate.execute("SET LOCAL hnsw.ef_search = 123");
            Object observed = entityManager.createNativeQuery("SHOW hnsw.ef_search").getSingleResult();
            assertThat(observed).hasToString("123");
            jdbcTemplate.execute("RESET hnsw.ef_search");

            Map<String, String> settings = new LinkedHashMap<>();
            for (String name : List.of("hnsw.ef_search", "hnsw.iterative_scan", "hnsw.max_scan_tuples",
                "hnsw.scan_mem_multiplier", "shared_buffers", "work_mem", "maintenance_work_mem",
                "effective_cache_size", "random_page_cost", "max_parallel_workers_per_gather",
                "max_parallel_maintenance_workers", "plan_cache_mode", "jit")) {
                settings.put(name, jdbcTemplate.queryForObject("SHOW " + name, String.class));
            }
            return settings;
        });
    }

    private String jdbcDriverVersion() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDriverVersion();
        }
    }

    private HnswIndex currentHnswIndex() {
        List<HnswIndex> indexes = jdbcTemplate.query("""
            SELECT indexname, indexdef FROM pg_indexes
            WHERE schemaname = current_schema() AND tablename = 'embeddings' AND indexdef ILIKE '%USING hnsw%'
            """, (resultSet, rowNumber) -> new HnswIndex(resultSet.getString(1), resultSet.getString(2)));
        if (!indexes.isEmpty()) {
            return indexes.get(0);
        }
        // 이전 실행이 Index를 지운 채 중단됐을 때를 위한 V32 정의
        return new HnswIndex(FALLBACK_HNSW_INDEX, "CREATE INDEX " + FALLBACK_HNSW_INDEX + " ON " + TEST_SCHEMA
            + ".embeddings USING hnsw (vector vector_cosine_ops)");
    }

    /**
     * 실제 문서와 합성 문서를 COPY로 적재하고, V32와 같은 정의로 HNSW Index를 다시 만들며 생성 시간을 잰다.
     */
    private LoadResult loadDataset(PermissionRecallDataset dataset, Long modelId, HnswIndex hnswIndex)
        throws Exception {
        jdbcTemplate.execute("TRUNCATE TABLE embeddings, document_chunks, document_versions, documents, users "
            + "RESTART IDENTITY CASCADE");
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + TEST_SCHEMA + "." + hnswIndex.name());
        Long ownerId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES ('permission-recall-545@example.com', 'password-hash', 'Permission Recall 545', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class);

        long loadStartedAt = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            PGConnection pgConnection = connection.unwrap(PGConnection.class);

            // 1. 문서·버전 id를 같게 두고 현재 버전을 연결한다. 모두 INDEXED·삭제 안 됨 상태다.
            copy(pgConnection, "documents (id, owner_user_id, title, document_type, source_type, status, visibility)",
                dataset.documents(), spec -> List.of(id(spec), ownerId, title(dataset, spec),
                    dataset.source(spec).documentType(), "UPLOAD", "INDEXED", "PRIVATE"));
            copy(pgConnection, "document_versions (id, document_id, version_no, title_snapshot, content_type, status, "
                    + "created_by)",
                dataset.documents(), spec -> List.of(id(spec), id(spec), 1, title(dataset, spec), "text/plain",
                    "INDEXED", ownerId));
            try (var statement = connection.createStatement()) {
                statement.execute("UPDATE documents SET current_version_id = id");
            }

            // 2. Chunk 본문은 원본 그대로 복제하고, Embedding은 실제 값 또는 합성 Vector를 그 자리에서 계산해 보낸다.
            CopyIn chunks = pgConnection.getCopyAPI().copyIn("COPY document_chunks (id, document_version_id, "
                + "chunk_index, chunk_text, token_count, char_start, char_end, page_no, section_title, content_hash, "
                + "metadata_json) FROM STDIN WITH (FORMAT csv)");
            CopyIn embeddings = null;
            long chunkId = 0;
            try {
                for (DocumentSpec spec : dataset.documents()) {
                    for (RealChunk chunk : dataset.source(spec).chunks()) {
                        chunkId++;
                        writeRow(chunks, List.of(chunkId, id(spec), chunk.chunkIndex(), chunk.chunkText(),
                            chunk.tokenCount(), chunk.charStart(), chunk.charEnd(), nullable(chunk.pageNo()),
                            nullable(chunk.sectionTitle()), chunk.contentHash(), nullable(chunk.metadataJson())));
                    }
                }
                chunks.endCopy();

                embeddings = pgConnection.getCopyAPI().copyIn("COPY embeddings (id, chunk_id, document_id, "
                    + "document_version_id, embedding_model_id, vector, dimension, vector_hash, status) "
                    + "FROM STDIN WITH (FORMAT csv)");
                chunkId = 0;
                for (DocumentSpec spec : dataset.documents()) {
                    List<RealChunk> sourceChunks = dataset.source(spec).chunks();
                    for (int index = 0; index < sourceChunks.size(); index++) {
                        chunkId++;
                        float[] vector = dataset.vector(spec, index);
                        writeRow(embeddings, List.of(chunkId, chunkId, id(spec), id(spec), modelId,
                            vectorString(vector), VECTOR_DIMENSION, EmbeddingVectorSupport.calculateHash(vector),
                            "ACTIVE"));
                    }
                }
                embeddings.endCopy();
            } finally {
                if (chunks.isActive()) {
                    chunks.cancelCopy();
                }
                if (embeddings != null && embeddings.isActive()) {
                    embeddings.cancelCopy();
                }
            }
        }
        jdbcTemplate.execute("ANALYZE documents");
        jdbcTemplate.execute("ANALYZE document_versions");
        jdbcTemplate.execute("ANALYZE document_chunks");
        double loadSeconds = (System.nanoTime() - loadStartedAt) / 1_000_000_000.0;

        // 3. V32에서 만든 것과 같은 정의(m·ef_construction 기본값)로 Index를 다시 만든다.
        long indexStartedAt = System.nanoTime();
        jdbcTemplate.execute(hnswIndex.definition());
        double indexBuildSeconds = (System.nanoTime() - indexStartedAt) / 1_000_000_000.0;
        jdbcTemplate.execute("ANALYZE embeddings");

        return new LoadResult(
            loadSeconds,
            indexBuildSeconds,
            jdbcTemplate.queryForObject("SELECT pg_relation_size('embeddings')", Long.class),
            jdbcTemplate.queryForObject("SELECT pg_relation_size('" + hnswIndex.name() + "')", Long.class),
            jdbcTemplate.queryForObject("SELECT pg_total_relation_size('embeddings')", Long.class)
        );
    }

    private static long id(DocumentSpec spec) {
        return spec.index() + 1L;
    }

    private static String title(PermissionRecallDataset dataset, DocumentSpec spec) {
        String title = dataset.source(spec).title();
        String decorated = spec.synthetic() ? title + " · 합성 " + spec.index() : title;
        return decorated.length() > 480 ? decorated.substring(0, 480) : decorated;
    }

    private static Object nullable(Object value) {
        return value == null ? CsvNull.INSTANCE : value;
    }

    private static <T> void copy(PGConnection connection, String target, List<T> items,
        Function<T, List<Object>> columns) throws Exception {
        CopyIn copyIn = connection.getCopyAPI().copyIn("COPY " + target + " FROM STDIN WITH (FORMAT csv)");
        try {
            for (T item : items) {
                writeRow(copyIn, columns.apply(item));
            }
            copyIn.endCopy();
        } finally {
            if (copyIn.isActive()) {
                copyIn.cancelCopy();
            }
        }
    }

    private static void writeRow(CopyIn copyIn, List<Object> values) throws Exception {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            Object value = values.get(index);
            if (value instanceof CsvNull) {
                continue;
            }
            if (value instanceof Number) {
                line.append(value);
            } else {
                line.append('"').append(value.toString().replace("\"", "\"\"")).append('"');
            }
        }
        byte[] bytes = line.append('\n').toString().getBytes(StandardCharsets.UTF_8);
        copyIn.writeToCopy(bytes, 0, bytes.length);
    }

    /** CSV에서 따옴표 없는 빈 칸(NULL)으로 쓸 값. */
    private enum CsvNull { INSTANCE }

    private PermissionRecallCorpus loadCorpus(Configuration configuration) throws IOException {
        return PermissionRecallCorpus.loadOrBuild(
            configuration.repositoryRoot(),
            configuration.corpusCachePath(),
            new PermissionRecallCorpus.Pipeline(
                documentParserRegistry, fixedSizeChunker, adaptiveEmbeddingBatchPlanner, embeddingClient),
            objectMapper
        );
    }

    private List<Question> loadQuestions(Configuration configuration) throws IOException {
        JsonNode root = objectMapper.readTree(configuration.questionsPath().toFile());
        List<Question> questions = new ArrayList<>();
        for (JsonNode node : root.path("questions")) {
            questions.add(new Question(node.path("id").asText(), node.path("source").asText(),
                node.path("question").asText()));
        }
        int limit = configuration.questionLimit() > 0 ? configuration.questionLimit() : questions.size();
        return List.copyOf(questions.subList(0, Math.min(limit, questions.size())));
    }

    /**
     * 운영 첫 질문과 같은 경로(대화 문맥 없이 원문 질문, 단건 Embedding API)로 질문 Vector를 만들고 캐시한다.
     */
    private Map<String, float[]> loadQuestionVectors(Configuration configuration, List<Question> questions)
        throws IOException {
        Map<String, float[]> vectors = new LinkedHashMap<>();
        Path cachePath = configuration.questionCachePath();
        if (Files.exists(cachePath)) {
            JsonNode cached = objectMapper.readTree(cachePath.toFile());
            cached.path("vectors").fields().forEachRemaining(entry ->
                vectors.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), float[].class)));
        }
        boolean changed = false;
        for (Question question : questions) {
            if (!vectors.containsKey(question.text())) {
                vectors.put(question.text(), embeddingClient.embed(question.text()));
                changed = true;
            }
        }
        if (changed) {
            writeJson(cachePath, Map.of("vectors", vectors));
        }
        return vectors;
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    /**
     * 운영 Repository Bean에 위임하면서 findTopK·단어 갈래 조회의 반환 행을 기록하는 Proxy를 만든다.
     */
    private VectorSearchRepository countingRepository(Recorder recorder) {
        return (VectorSearchRepository) Proxy.newProxyInstance(
            VectorSearchRepository.class.getClassLoader(),
            new Class<?>[] {VectorSearchRepository.class},
            (proxy, method, arguments) -> {
                boolean topK = "findTopK".equals(method.getName());
                Object result;
                try {
                    if (topK) {
                        setSortForTopK(currentMethod, false);
                    }
                    result = method.invoke(vectorSearchRepository, arguments);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                } finally {
                    if (topK) {
                        setSortForTopK(currentMethod, true);
                    }
                }
                switch (method.getName()) {
                    case "findTopK" -> recorder.recordTopK((List<?>) result);
                    case "findLexicalCandidates" -> recorder.recordLexical((List<?>) result);
                    default -> { }
                }
                return result;
            });
    }

    private static String topKSql() {
        try {
            return VectorSearchRepository.class
                .getMethod("findTopK", String.class, Long.class, List.class, int.class)
                .getAnnotation(Query.class)
                .value();
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 운영 VectorSearchQueryService와 같은 규칙(StringBuilder의 float 표기)으로 Vector 문자열을 만든다. */
    static String vectorString(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 12).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private static List<Long> chunkIds(List<VectorSearchCandidate> candidates) {
        return candidates.stream().map(VectorSearchCandidate::chunkId).toList();
    }

    private static <T> Timed<T> timed(Supplier<T> work) {
        long startedAt = System.nanoTime();
        T value = work.get();
        return new Timed<>(value, (System.nanoTime() - startedAt) / 1_000_000.0);
    }

    /** Plan Cache 모드. */
    enum PlanCacheMode {
        CUSTOM("force_custom_plan"),
        GENERIC("force_generic_plan");

        private final String setting;

        PlanCacheMode(String setting) {
            this.setting = setting;
        }

        String setting() {
            return setting;
        }
    }

    /**
     * 측정 방식.
     *
     * <ul>
     *   <li>{@code exact}: HNSW Index를 지운 상태. Recall 정답이다.</li>
     *   <li>{@code planner_default}·{@code planner_default_generic}: Index가 있고 설정은 기본값. 운영이 실제로 고르는 계획을
     *       Custom Plan과 Generic Plan(Prepared Statement 재사용 후)으로 잰다.</li>
     *   <li>{@code hnsw_*}·{@code iterative_*}: {@code enable_sort=off}로 HNSW를 쓰게 한 뒤 ef_search·iterative_scan을 바꾼다.</li>
     * </ul>
     */
    enum Method {
        EXACT("exact", PlanCacheMode.CUSTOM, false, 0, "off"),
        PLANNER_DEFAULT("planner_default", PlanCacheMode.CUSTOM, false, 40, "off"),
        PLANNER_DEFAULT_GENERIC("planner_default_generic", PlanCacheMode.GENERIC, false, 40, "off"),
        HNSW_EF40("hnsw_ef40", PlanCacheMode.CUSTOM, true, 40, "off"),
        HNSW_EF100("hnsw_ef100", PlanCacheMode.CUSTOM, true, 100, "off"),
        HNSW_EF200("hnsw_ef200", PlanCacheMode.CUSTOM, true, 200, "off"),
        ITERATIVE_RELAXED("iterative_relaxed", PlanCacheMode.CUSTOM, true, 40, "relaxed_order"),
        ITERATIVE_STRICT("iterative_strict", PlanCacheMode.CUSTOM, true, 40, "strict_order");

        private final String label;
        private final PlanCacheMode planCacheMode;
        private final boolean forceHnsw;
        private final int efSearch;
        private final String iterativeScan;

        Method(String label, PlanCacheMode planCacheMode, boolean forceHnsw, int efSearch, String iterativeScan) {
            this.label = label;
            this.planCacheMode = planCacheMode;
            this.forceHnsw = forceHnsw;
            this.efSearch = efSearch;
            this.iterativeScan = iterativeScan;
        }

        String label() {
            return label;
        }

        PlanCacheMode planCacheMode() {
            return planCacheMode;
        }

        boolean forceHnsw() {
            return forceHnsw;
        }

        int efSearch() {
            return efSearch;
        }

        String iterativeScan() {
            return iterativeScan;
        }

        static Method fromLabel(String label) {
            return Arrays.stream(values()).filter(method -> method.label.equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 방식: " + label));
        }
    }

    /** 서비스 호출 한 번이 Repository에서 받은 행. */
    static final class Recorder {
        private List<Long> topKChunkIds = List.of();
        private int lexicalRows;

        void reset() {
            topKChunkIds = List.of();
            lexicalRows = 0;
        }

        void recordTopK(List<?> rows) {
            topKChunkIds = rows.stream().map(row -> ((VectorSearchRow) row).getChunkId()).toList();
        }

        void recordLexical(List<?> rows) {
            lexicalRows = rows.size();
        }

        List<Long> topKChunkIds() {
            return topKChunkIds;
        }

        int lexicalRows() {
            return lexicalRows;
        }
    }

    /** 시스템 속성({@code vector.search.performance.permission.*})으로 정하는 실행 설정. 기본값은 이 시험(#545) 측정 조건이다. */
    record Configuration(
        Path repositoryRoot,
        Path corpusCachePath,
        Path questionsPath,
        Path questionCachePath,
        Path outputPath,
        Path planOutputPath,
        List<String> scales,
        List<Double> ratios,
        List<Integer> topKs,
        List<Method> methods,
        int warmUpRuns,
        int measuredRuns,
        int genericWarmUpRuns,
        int genericMeasuredRuns,
        int questionLimit,
        long seed
    ) {

        static Configuration fromSystemProperties() {
            return new Configuration(
                Path.of(property("repository-root", "..")),
                Path.of(property("corpus-cache", "build/reports/permission-recall/real-corpus.json")),
                Path.of(property("questions", "../docs/test-results/evidence/issue-545/questions.json")),
                Path.of(property("question-cache", "build/reports/permission-recall/question-vectors.json")),
                Path.of(property("output", "build/reports/permission-recall/results.json")),
                Path.of(property("plan-output", "build/reports/permission-recall/plans.json")),
                split(property("scales", "real,50000")),
                split(property("ratios", "1.0,0.1,0.01,0.001")).stream().map(Double::parseDouble).toList(),
                split(property("top-ks", "5,10,20")).stream().map(Integer::parseInt).toList(),
                split(property("methods", "exact,planner_default,planner_default_generic,"
                    + "hnsw_ef40,hnsw_ef100,hnsw_ef200,iterative_relaxed,iterative_strict"))
                    .stream().map(Method::fromLabel).toList(),
                Integer.parseInt(property("warm-up", "1")),
                Integer.parseInt(property("measured-runs", "3")),
                Integer.parseInt(property("generic-warm-up", "0")),
                Integer.parseInt(property("generic-measured-runs", "1")),
                Integer.parseInt(property("question-limit", "0")),
                Long.parseLong(property("seed", "545"))
            );
        }

        private static String property(String name, String defaultValue) {
            return System.getProperty(PROPERTY_PREFIX + name, defaultValue);
        }

        private static List<String> split(String value) {
            return Arrays.stream(value.split(",")).map(String::strip).filter(item -> !item.isEmpty()).toList();
        }
    }

    /** 고정 질문 한 건과 그 질문을 만든 원본 문서 경로. */
    record Question(String id, String source, String text) {
    }

    /** HNSW Index 이름과 다시 만들 때 쓰는 정의(V32와 같음). */
    record HnswIndex(String name, String definition) {
    }

    /** 서비스 호출 결과와 걸린 시간(ms). */
    record Timed<T>(T value, double millis) {
    }

    /** 데이터 적재·Index 생성 시간과 크기. */
    record LoadResult(double loadSeconds, double indexBuildSeconds, long tableBytes, long indexBytes,
        long totalBytes) {
    }

    /** 질문 한 건 × 방식 한 개의 SQL 후보·최종 결과·지연·실행 계획. ID 목록은 첫 측정 결과다. */
    record QuestionOutcome(
        List<Long> sqlChunkIds,
        List<Long> vectorFinalIds,
        List<Long> hybridFinalIds,
        int hybridLexicalRows,
        List<Double> vectorMillis,
        List<Double> hybridMillis,
        boolean stable,
        PlanSummary plan,
        String rawPlan
    ) {
    }

    /** 조합마다 첫 질문의 EXPLAIN 원문(별도 파일로 저장). */
    record PlanSample(String scale, double ratio, int topK, String method, String questionId, String plan) {
    }

    /** 허용 비율별 실제 허용 문서·청크 수. */
    record RatioInfo(double ratio, int permittedDocuments, int permittedChunks) {
    }

    /** 규모 한 개의 데이터 구성·합성 비율·Index 비용. */
    record ScaleResult(
        String scale,
        int documents,
        int realDocuments,
        int syntheticDocuments,
        int chunks,
        int realChunks,
        double syntheticChunkRatio,
        double syntheticSimilarityLow,
        double syntheticSimilarityHigh,
        double loadSeconds,
        double indexBuildSeconds,
        long tableBytes,
        long indexBytes,
        long totalBytes,
        List<RatioInfo> ratios
    ) {
    }

    /** 측정 당시 운영 검색 설정값(application.yml 기본값). */
    record SearchSettings(
        double minSimilarity,
        int maxChunksPerDocument,
        int candidatePoolMultiplier,
        boolean hybridEnabled,
        double hybridVectorMinSimilarity,
        double hybridMinCoverage,
        double hybridCoverageWeight,
        int hybridLexicalCandidatePoolMultiplier
    ) {
    }

    /** 결과 파일(results.json) 전체 구조. */
    record Report(
        String measuredAt,
        String postgresVersion,
        String pgvectorVersion,
        Map<String, String> postgresSettings,
        String osArch,
        String javaVersion,
        String jdbcDriverVersion,
        String embeddingModel,
        int corpusDocuments,
        int corpusChunks,
        List<String> skippedSources,
        double corpusEmbeddingSeconds,
        Distribution crossDocumentNearestSimilarity,
        SearchSettings searchSettings,
        String hnswIndexDefinition,
        Configuration configuration,
        List<ScaleResult> scales,
        List<CombinationSummary> summaries,
        List<MeasurementRow> rows
    ) {
    }

    /** 실행 계획에서 HNSW 사용 여부와 필터로 버려진 행 등 판정에 필요한 값만 추린 것. */
    record PlanSummary(
        boolean usesHnsw,
        List<String> nodeTypes,
        List<String> indexNames,
        Long hnswActualRows,
        Long hnswRowsRemovedByFilter,
        long resultRows,
        double planningMillis,
        double executionMillis,
        long sharedHitBlocks,
        long sharedReadBlocks
    ) {

        static PlanSummary parse(JsonNode explain, String hnswIndexName) {
            JsonNode root = explain.get(0);
            JsonNode plan = root.path("Plan");
            List<String> nodeTypes = new ArrayList<>();
            List<String> indexNames = new ArrayList<>();
            List<JsonNode> hnswNodes = new ArrayList<>();
            collect(plan, hnswIndexName, nodeTypes, indexNames, hnswNodes);
            JsonNode hnswNode = hnswNodes.isEmpty() ? null : hnswNodes.get(0);
            return new PlanSummary(
                hnswNode != null,
                List.copyOf(nodeTypes),
                List.copyOf(indexNames),
                hnswNode == null ? null : hnswNode.path("Actual Rows").asLong(),
                hnswNode == null ? null : hnswNode.path("Rows Removed by Filter").asLong(0),
                plan.path("Actual Rows").asLong(),
                root.path("Planning Time").asDouble(),
                root.path("Execution Time").asDouble(),
                plan.path("Shared Hit Blocks").asLong(),
                plan.path("Shared Read Blocks").asLong()
            );
        }

        private static void collect(JsonNode node, String hnswIndexName, List<String> nodeTypes,
            List<String> indexNames, List<JsonNode> hnswNodes) {
            nodeTypes.add(node.path("Node Type").asText());
            if (node.has("Index Name")) {
                String indexName = node.path("Index Name").asText();
                indexNames.add(indexName);
                if (indexName.equals(hnswIndexName)) {
                    hnswNodes.add(node);
                }
            }
            for (JsonNode child : node.path("Plans")) {
                collect(child, hnswIndexName, nodeTypes, indexNames, hnswNodes);
            }
        }
    }

    /** 질문 한 건 × 조합 한 개의 측정 행. Recall은 같은 조건의 Exact 결과를 정답으로 계산한다. */
    record MeasurementRow(
        String scale,
        double ratio,
        int permittedDocuments,
        int permittedChunks,
        int topK,
        int candidateLimit,
        String method,
        String questionId,
        String questionSource,
        int sqlRows,
        int vectorFinal,
        int hybridFinal,
        int hybridLexicalRows,
        int exactSqlRows,
        int exactVectorFinal,
        int exactHybridFinal,
        Double sqlRecall,
        Double vectorFinalRecall,
        Double hybridFinalRecall,
        List<Double> vectorMillis,
        List<Double> hybridMillis,
        boolean stable,
        PlanSummary plan
    ) {

        static MeasurementRow of(String scale, double ratio, int permittedDocuments, int permittedChunks, int topK,
            int candidateLimit, Method method, Question question, QuestionOutcome outcome, QuestionOutcome exact) {
            return new MeasurementRow(
                scale, ratio, permittedDocuments, permittedChunks, topK, candidateLimit, method.label(),
                question.id(), question.source(),
                outcome.sqlChunkIds().size(), outcome.vectorFinalIds().size(), outcome.hybridFinalIds().size(),
                outcome.hybridLexicalRows(),
                exact.sqlChunkIds().size(), exact.vectorFinalIds().size(), exact.hybridFinalIds().size(),
                PermissionRecallMetrics.recall(outcome.sqlChunkIds(), exact.sqlChunkIds()),
                PermissionRecallMetrics.recall(outcome.vectorFinalIds(), exact.vectorFinalIds()),
                PermissionRecallMetrics.recall(outcome.hybridFinalIds(), exact.hybridFinalIds()),
                outcome.vectorMillis(), outcome.hybridMillis(), outcome.stable(), outcome.plan()
            );
        }
    }

    /** 분포 요약(오름차순 정렬 후 최근접 순위 백분위). */
    record Distribution(int count, double min, double p10, double p50, double p90, double p95, double max,
        double mean) {

        static Distribution of(double[] values) {
            double[] sorted = values.clone();
            Arrays.sort(sorted);
            return new Distribution(sorted.length, sorted[0],
                PermissionRecallMetrics.percentile(sorted, 0.10), PermissionRecallMetrics.percentile(sorted, 0.50),
                PermissionRecallMetrics.percentile(sorted, 0.90), PermissionRecallMetrics.percentile(sorted, 0.95),
                sorted[sorted.length - 1], Arrays.stream(sorted).average().orElse(Double.NaN));
        }
    }

    /** 규모 × 비율 × topK × 방식 한 조합의 질문 전체 요약. */
    record CombinationSummary(
        String scale,
        double ratio,
        int permittedDocuments,
        int permittedChunks,
        int topK,
        int candidateLimit,
        String method,
        int questions,
        double sqlRowsMean,
        int sqlRowsMin,
        int sqlRowsMax,
        double vectorFinalMean,
        int vectorShortQuestions,
        double hybridFinalMean,
        int hybridShortQuestions,
        double exactVectorFinalMean,
        double exactHybridFinalMean,
        Double sqlRecallMean,
        Double sqlRecallMin,
        Double vectorFinalRecallMean,
        Double vectorFinalRecallMin,
        Double hybridFinalRecallMean,
        Double hybridFinalRecallMin,
        double vectorP50Millis,
        double vectorP95Millis,
        double hybridP50Millis,
        double hybridP95Millis,
        int hnswPlanQuestions,
        int unstableQuestions
    ) {

        static List<CombinationSummary> summarize(List<MeasurementRow> rows) {
            Map<String, List<MeasurementRow>> groups = new LinkedHashMap<>();
            for (MeasurementRow row : rows) {
                groups.computeIfAbsent(row.scale() + "|" + row.ratio() + "|" + row.topK() + "|" + row.method(),
                    key -> new ArrayList<>()).add(row);
            }
            List<CombinationSummary> summaries = new ArrayList<>();
            for (List<MeasurementRow> group : groups.values()) {
                MeasurementRow first = group.get(0);
                summaries.add(new CombinationSummary(
                    first.scale(), first.ratio(), first.permittedDocuments(), first.permittedChunks(), first.topK(),
                    first.candidateLimit(), first.method(), group.size(),
                    group.stream().mapToInt(MeasurementRow::sqlRows).average().orElse(0),
                    group.stream().mapToInt(MeasurementRow::sqlRows).min().orElse(0),
                    group.stream().mapToInt(MeasurementRow::sqlRows).max().orElse(0),
                    group.stream().mapToInt(MeasurementRow::vectorFinal).average().orElse(0),
                    (int) group.stream().filter(row -> row.vectorFinal() < row.topK()).count(),
                    group.stream().mapToInt(MeasurementRow::hybridFinal).average().orElse(0),
                    (int) group.stream().filter(row -> row.hybridFinal() < row.topK()).count(),
                    group.stream().mapToInt(MeasurementRow::exactVectorFinal).average().orElse(0),
                    group.stream().mapToInt(MeasurementRow::exactHybridFinal).average().orElse(0),
                    PermissionRecallMetrics.mean(group.stream().map(MeasurementRow::sqlRecall).toList()),
                    PermissionRecallMetrics.min(group.stream().map(MeasurementRow::sqlRecall).toList()),
                    PermissionRecallMetrics.mean(group.stream().map(MeasurementRow::vectorFinalRecall).toList()),
                    PermissionRecallMetrics.min(group.stream().map(MeasurementRow::vectorFinalRecall).toList()),
                    PermissionRecallMetrics.mean(group.stream().map(MeasurementRow::hybridFinalRecall).toList()),
                    PermissionRecallMetrics.min(group.stream().map(MeasurementRow::hybridFinalRecall).toList()),
                    PermissionRecallMetrics.percentile(latencies(group, true), 0.50),
                    PermissionRecallMetrics.percentile(latencies(group, true), 0.95),
                    PermissionRecallMetrics.percentile(latencies(group, false), 0.50),
                    PermissionRecallMetrics.percentile(latencies(group, false), 0.95),
                    (int) group.stream().filter(row -> row.plan().usesHnsw()).count(),
                    (int) group.stream().filter(row -> !row.stable()).count()
                ));
            }
            return List.copyOf(summaries);
        }

        private static double[] latencies(List<MeasurementRow> group, boolean vector) {
            return group.stream()
                .flatMap(row -> (vector ? row.vectorMillis() : row.hybridMillis()).stream())
                .mapToDouble(Double::doubleValue)
                .sorted()
                .toArray();
        }
    }
}
