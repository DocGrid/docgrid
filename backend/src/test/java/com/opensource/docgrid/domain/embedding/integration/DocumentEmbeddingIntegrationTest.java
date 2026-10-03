package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.opensource.docgrid.domain.document.enums.DocumentVersionStatus;
import com.opensource.docgrid.domain.embedding.client.EmbeddingClient;
import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitBreaker;
import com.opensource.docgrid.domain.embedding.client.EmbeddingProviderCircuitMetrics;
import com.opensource.docgrid.domain.embedding.config.EmbeddingProviderCircuitBreakerProperties;
import com.opensource.docgrid.domain.embedding.dto.request.CreateDocumentEmbeddingsRequest;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchItemResponse;
import com.opensource.docgrid.domain.embedding.dto.response.EmbedBatchServerResponse;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingService.EmbeddingResult;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 실제 OpenSQL에서 Chunk Embedding의 Vector 저장, 실패 원자성과 동시 Transaction 수렴을 검증한다.
 *
 * <p>격리 Schema와 결정적인 외부 Client Stub을 사용해 Version·Model별 전체 Embedding Set만 저장되고,
 * 같은 실행의 순차·동시 재호출이 하나의 결과로 수렴하는지 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@Import(DocumentEmbeddingIntegrationTest.EmbeddingClientTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Document Embedding OpenSQL 통합 테스트")
class DocumentEmbeddingIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_document_embedding_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final int CONCURRENT_REQUESTS = 2;
    private static final long TIMEOUT_SECONDS = 10;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DocumentEmbeddingService documentEmbeddingService;
    @Autowired private DeterministicEmbeddingClient embeddingClient;

    @DynamicPropertySource
    static void configureEmbedding(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-embedding-integration-test-secret-key-2026");
        registry.add("embedding.document.batch-size", () -> 2);
    }

    @BeforeEach
    void resetState() {
        embeddingClient.reset();
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                embeddings,
                indexing_events,
                document_chunks,
                embedding_job_attempts,
                embedding_jobs,
                document_versions,
                documents,
                worker_nodes,
                users
            RESTART IDENTITY CASCADE
            """);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Chunk 전체를 vector(1024)와 EMBEDDING 상태로 저장하고 재호출을 재생한다")
    void createEmbeddings_persistsVectorSetAndReplays() {
        ExecutionContext context = insertExecution();
        CreateDocumentEmbeddingsRequest request =
            new CreateDocumentEmbeddingsRequest(context.workerId(), CLAIM_TOKEN);

        EmbeddingResult created =
            documentEmbeddingService.createEmbeddings(context.jobId(), context.attemptId(), request);
        EmbeddingResult replayed =
            documentEmbeddingService.createEmbeddings(context.jobId(), context.attemptId(), request);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.response()).isEqualTo(created.response());
        assertThat(created.response().embeddingCount()).isEqualTo(3);
        assertThat(created.response().versionStatus()).isEqualTo(DocumentVersionStatus.EMBEDDING);
        assertThat(embeddingClient.callCount()).isEqualTo(2);

        assertThat(jdbcTemplate.queryForList("""
            SELECT document_id, document_version_id, embedding_model_id, dimension, status,
                   vector_dims(vector) AS vector_dimension
            FROM embeddings
            WHERE document_version_id = ?
            ORDER BY chunk_id
            """, context.versionId()))
            .containsExactly(
                embeddingRow(context),
                embeddingRow(context),
                embeddingRow(context)
            );
        assertThat(jdbcTemplate.queryForList("""
            SELECT vector_hash
            FROM embeddings
            WHERE document_version_id = ?
            """, String.class, context.versionId()))
            .allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        assertThat(queryString("SELECT status FROM document_versions WHERE id = ?", context.versionId()))
            .isEqualTo("EMBEDDING");
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("PROCESSING");
        assertThat(queryString("SELECT status FROM embedding_job_attempts WHERE id = ?", context.attemptId()))
            .isEqualTo("STARTED");
        assertThat(eventCount(context.jobId(), "EMBEDDING_STARTED")).isOne();
    }

    @Test
    @DisplayName("두 번째 Batch 외부 호출이 실패하면 Embedding 행을 하나도 저장하지 않는다")
    void createEmbeddings_externalFailureLeavesNoPartialRows() {
        ExecutionContext context = insertExecution();
        CreateDocumentEmbeddingsRequest request =
            new CreateDocumentEmbeddingsRequest(context.workerId(), CLAIM_TOKEN);
        embeddingClient.failOnText("세 번째");

        assertThatThrownBy(() ->
            documentEmbeddingService.createEmbeddings(context.jobId(), context.attemptId(), request))
            .isInstanceOfSatisfying(DocGridException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE));

        assertThat(embeddingCount(context.versionId())).isZero();
        assertThat(embeddingClient.callCount()).isEqualTo(2);
        assertThat(queryString("SELECT status FROM document_versions WHERE id = ?", context.versionId()))
            .isEqualTo("EMBEDDING");
        assertThat(eventCount(context.jobId(), "EMBEDDING_STARTED")).isOne();
    }

    @Test
    @DisplayName("같은 실행의 두 요청은 생성과 재생 하나씩 및 단일 Embedding Set으로 수렴한다")
    void createEmbeddings_concurrentRequestsConverge() throws Exception {
        ExecutionContext context = insertExecution();
        CreateDocumentEmbeddingsRequest request =
            new CreateDocumentEmbeddingsRequest(context.workerId(), CLAIM_TOKEN);
        embeddingClient.armBarrier("첫 번째", CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        List<EmbeddingResult> results;
        try {
            List<Future<EmbeddingResult>> futures = List.of(
                executor.submit(() -> documentEmbeddingService.createEmbeddings(
                    context.jobId(), context.attemptId(), request
                )),
                executor.submit(() -> documentEmbeddingService.createEmbeddings(
                    context.jobId(), context.attemptId(), request
                ))
            );
            results = List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            embeddingClient.disarmBarrier();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).filteredOn(EmbeddingResult::created).hasSize(1);
        assertThat(results).filteredOn(result -> !result.created()).hasSize(1);
        assertThat(results).extracting(result -> result.response().embeddingCount()).containsOnly(3);
        assertThat(embeddingCount(context.versionId())).isEqualTo(3);
        assertThat(embeddingClient.callCount()).isEqualTo(4);
        assertThat(eventCount(context.jobId(), "EMBEDDING_STARTED")).isOne();
    }

    private ExecutionContext insertExecution() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Embedding Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "embedding-" + suffix + "@example.com");
        Long workerId = jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, status, last_heartbeat_at, started_at, created_at, updated_at
            )
            VALUES ('embedding-worker', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, suffix);
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at, updated_at
            )
            VALUES (?, 'Embedding Test Document', 'TXT', 'UPLOAD', 'INDEXING', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, 1, 'Embedding Test Version', 'text/plain', 'CHUNKED', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
        insertChunks(versionId);
        Long embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE AND is_searchable = TRUE
            LIMIT 1
            """, Long.class);
        Long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count, max_retry_count,
                locked_by_worker_id, locked_at, lock_expires_at, claim_token, started_at, created_at, updated_at
            )
            VALUES (?, ?, 'PROCESSING', 0, 0, 3, ?, CURRENT_TIMESTAMP,
                    TIMESTAMP '2099-01-01 00:00:00', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, embeddingModelId, workerId, CLAIM_TOKEN);
        Long attemptId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_job_attempts (
                embedding_job_id, worker_node_id, attempt_no, claim_token, status,
                started_at, created_at, updated_at
            )
            VALUES (?, ?, 1, ?, 'STARTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, jobId, workerId, CLAIM_TOKEN);
        return new ExecutionContext(workerId, jobId, attemptId, documentId, versionId, embeddingModelId);
    }

    private void insertChunks(Long versionId) {
        jdbcTemplate.update("""
            INSERT INTO document_chunks (
                document_version_id, chunk_index, chunk_text, token_count, char_start, char_end,
                content_hash, created_at, updated_at
            )
            VALUES
                (?, 0, '첫 번째', 1, 0, 4, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (?, 1, '두 번째', 1, 4, 8, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (?, 2, '세 번째', 1, 8, 12, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            versionId, CONTENT_HASH,
            versionId, CONTENT_HASH,
            versionId, CONTENT_HASH
        );
    }

    private Map<String, Object> embeddingRow(ExecutionContext context) {
        return Map.of(
            "document_id", context.documentId(),
            "document_version_id", context.versionId(),
            "embedding_model_id", context.embeddingModelId(),
            "dimension", VECTOR_DIMENSION,
            "status", "ACTIVE",
            "vector_dimension", VECTOR_DIMENSION
        );
    }

    private int embeddingCount(Long versionId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            Integer.class,
            versionId
        );
    }

    private String queryString(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }

    private int eventCount(Long jobId, String eventType) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = ?",
            Integer.class,
            jobId,
            eventType
        );
    }

    /**
     * 통합 테스트 실행 Context의 Worker, Job, Attempt, Document, Version과 Model 식별자를 묶는다.
     */
    private record ExecutionContext(
        Long workerId,
        Long jobId,
        Long attemptId,
        Long documentId,
        Long versionId,
        Long embeddingModelId
    ) {
    }

    /**
     * 실제 DB Transaction 밖의 외부 호출을 결정적으로 제어하는 테스트 Client Bean을 제공한다.
     */
    @TestConfiguration
    static class EmbeddingClientTestConfig {

        @Bean
        @Primary
        DeterministicEmbeddingClient deterministicEmbeddingClient() {
            return new DeterministicEmbeddingClient();
        }
    }

    /**
     * Batch별 1024차원 Vector를 만들고 실패 지점과 두 요청의 동시 진입 Barrier를 제어한다.
     */
    static class DeterministicEmbeddingClient extends EmbeddingClient {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile String failureText;
        private volatile String barrierText;
        private volatile CyclicBarrier barrier;

        DeterministicEmbeddingClient() {
            super(
                RestClient.builder().build(),
                RestClient.builder().build(),
                disabledCircuitBreaker()
            );
        }

        private static EmbeddingProviderCircuitBreaker disabledCircuitBreaker() {
            EmbeddingProviderCircuitBreakerProperties properties =
                new EmbeddingProviderCircuitBreakerProperties();
            properties.setEnabled(false);
            return new EmbeddingProviderCircuitBreaker(
                properties,
                Clock.systemUTC(),
                new EmbeddingProviderCircuitMetrics(new SimpleMeterRegistry())
            );
        }

        @Override
        public EmbedBatchServerResponse embedBatch(List<String> texts, int batchSize) {
            calls.incrementAndGet();
            if (texts.contains(failureText)) {
                throw new DocGridException(ErrorCode.EMBEDDING_SERVER_UNAVAILABLE);
            }

            CyclicBarrier currentBarrier = barrier;
            if (currentBarrier != null && texts.contains(barrierText)) {
                try {
                    currentBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("Embedding 통합 테스트 Barrier 대기에 실패했습니다.", exception);
                }
            }

            List<EmbedBatchItemResponse> embeddings = new ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                String text = texts.get(index);
                float[] vector = new float[VECTOR_DIMENSION];
                vector[0] = text.hashCode();
                vector[1] = text.length();
                embeddings.add(new EmbedBatchItemResponse(index, vector));
            }
            return new EmbedBatchServerResponse("BAAI/bge-m3", embeddings);
        }

        int callCount() {
            return calls.get();
        }

        void failOnText(String text) {
            failureText = text;
        }

        void armBarrier(String text, int parties) {
            barrierText = text;
            barrier = new CyclicBarrier(parties);
        }

        void disarmBarrier() {
            CyclicBarrier currentBarrier = barrier;
            barrier = null;
            barrierText = null;
            if (currentBarrier != null) {
                currentBarrier.reset();
            }
        }

        void reset() {
            disarmBarrier();
            failureText = null;
            calls.set(0);
        }
    }
}
