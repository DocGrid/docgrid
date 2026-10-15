package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.document.repository.DocumentRepository;
import com.opensource.docgrid.domain.embedding.dto.request.CompleteDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingCompletionResponse;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingCompletionService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.global.support.TruncateDeadlockRetry;

/**
 * 실제 OpenSQL에서 문서 인덱싱 완료가 검색 가시성과 Version별 Embedding 상태를 원자적으로 전환하는지 검증한다.
 *
 * <p>격리 Schema에 완료 직전 상태를 직접 구성하고 완료 Service를 호출해 최초 Version 공개와 새 Version
 * 교체가 권한 pre-filter 및 pgvector 검색 조건까지 일관되게 반영되는지 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Document 인덱싱 완료 OpenSQL 통합 테스트")
class DocumentIndexingCompletionIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_index_completion_integration_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final int CONCURRENT_REQUESTS = 2;
    private static final long TIMEOUT_SECONDS = 10;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DocumentIndexingCompletionService completionService;
    @Autowired private DocumentRepository documentRepository;
    @Autowired private VectorSearchQueryService vectorSearchQueryService;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-index-completion-integration-test-secret-key-2026");
    }

    @BeforeEach
    void resetState() {
        TruncateDeadlockRetry.execute(jdbcTemplate, """
            TRUNCATE TABLE
                search_results,
                search_queries,
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
    @DisplayName("최초 Version 완료 전에는 검색되지 않고 완료 후 현재 ACTIVE Version으로 검색된다")
    void completeFirstVersion_makesDocumentSearchable() {
        ExecutionContext context = insertFirstVersionExecution();

        assertThat(documentRepository.findReadableDocumentIds(context.userId(), List.of("INDEXED"))).isEmpty();
        assertThat(search(context)).isEmpty();

        DocumentIndexingCompletionResponse response = completionService.complete(
            context.jobId(),
            context.attemptId(),
            new CompleteDocumentIndexingRequest(context.workerId(), CLAIM_TOKEN)
        );

        assertThat(response.jobStatus().name()).isEqualTo("INDEXED");
        assertThat(response.attemptStatus().name()).isEqualTo("SUCCESS");
        assertThat(response.versionStatus().name()).isEqualTo("INDEXED");
        assertThat(documentRepository.findReadableDocumentIds(context.userId(), List.of("INDEXED")))
            .containsExactly(context.documentId());
        assertThat(search(context))
            .extracting(VectorSearchCandidate::chunkText)
            .containsExactly("최초 검색 본문");
        assertThat(queryString("SELECT status FROM documents WHERE id = ?", context.documentId()))
            .isEqualTo("INDEXED");
        assertThat(queryLong(
            "SELECT current_version_id FROM documents WHERE id = ?",
            context.documentId()
        )).isEqualTo(context.targetVersionId());
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.targetVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(indexedEventCount(context.jobId())).isOne();
        assertThat(completionTimestampsMatch(context)).isTrue();
    }

    @Test
    @DisplayName("새 Version 완료 전에는 이전 본문을 검색하고 완료 후 새 ACTIVE 본문만 검색한다")
    void completeReplacementVersion_switchesCurrentSearchSet() {
        ExecutionContext context = insertReplacementVersionExecution();

        assertThat(search(context))
            .extracting(VectorSearchCandidate::chunkText)
            .containsExactly("이전 검색 본문");

        completionService.complete(
            context.jobId(),
            context.attemptId(),
            new CompleteDocumentIndexingRequest(context.workerId(), CLAIM_TOKEN)
        );

        assertThat(queryLong(
            "SELECT current_version_id FROM documents WHERE id = ?",
            context.documentId()
        )).isEqualTo(context.targetVersionId());
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.previousVersionId()
        )).isEqualTo("STALE");
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.targetVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(search(context))
            .extracting(VectorSearchCandidate::chunkText)
            .containsExactly("새 검색 본문");
        assertThat(indexedEventCount(context.jobId())).isOne();
    }

    @Test
    @DisplayName("같은 실행의 두 완료 요청은 동일 응답과 단일 INDEXED 이벤트로 수렴한다")
    void completeConcurrently_convergesToStoredResponse() throws Exception {
        ExecutionContext context = insertFirstVersionExecution();
        CompleteDocumentIndexingRequest request =
            new CompleteDocumentIndexingRequest(context.workerId(), CLAIM_TOKEN);
        CyclicBarrier startBarrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        List<DocumentIndexingCompletionResponse> responses;
        try {
            List<Future<DocumentIndexingCompletionResponse>> futures = List.of(
                executor.submit(() -> completeAfterBarrier(context, request, startBarrier)),
                executor.submit(() -> completeAfterBarrier(context, request, startBarrier))
            );
            responses = List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(responses).hasSize(2);
        assertThat(responses.get(1)).isEqualTo(responses.get(0));
        assertThat(indexedEventCount(context.jobId())).isOne();
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("INDEXED");
        assertThat(queryString(
            "SELECT status FROM embedding_job_attempts WHERE id = ?",
            context.attemptId()
        )).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("INDEXED 이벤트 저장 실패 시 이전 STALE 처리와 모든 완료 상태를 Rollback한다")
    void complete_rollsBackAllChanges_whenFinalEventInsertFails() {
        ExecutionContext context = insertReplacementVersionExecution();
        installFailingIndexedEventTrigger();

        try {
            assertThatThrownBy(() -> completionService.complete(
                context.jobId(),
                context.attemptId(),
                new CompleteDocumentIndexingRequest(context.workerId(), CLAIM_TOKEN)
            )).isInstanceOf(RuntimeException.class);
        } finally {
            removeFailingIndexedEventTrigger();
        }

        assertThat(queryLong(
            "SELECT current_version_id FROM documents WHERE id = ?",
            context.documentId()
        )).isEqualTo(context.previousVersionId());
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.previousVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.targetVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            context.targetVersionId()
        )).isEqualTo("EMBEDDING");
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("PROCESSING");
        assertThat(queryString(
            "SELECT status FROM embedding_job_attempts WHERE id = ?",
            context.attemptId()
        )).isEqualTo("STARTED");
        assertThat(indexedEventCount(context.jobId())).isZero();
    }

    private ExecutionContext insertFirstVersionExecution() {
        BaseContext base = insertBase("UPLOADED");
        Long versionId = insertVersion(base.documentId(), base.userId(), 1, "EMBEDDING");
        setCurrentVersion(base.documentId(), versionId);
        insertChunkAndEmbedding(
            base.documentId(),
            versionId,
            base.embeddingModelId(),
            "최초 검색 본문",
            1.0f
        );
        JobContext job = insertProcessingJob(
            versionId,
            base.embeddingModelId(),
            base.workerId()
        );
        return new ExecutionContext(
            base.userId(),
            base.workerId(),
            base.documentId(),
            null,
            versionId,
            base.embeddingModelId(),
            job.jobId(),
            job.attemptId()
        );
    }

    private ExecutionContext insertReplacementVersionExecution() {
        BaseContext base = insertBase("INDEXED");
        Long previousVersionId = insertVersion(
            base.documentId(),
            base.userId(),
            1,
            "INDEXED"
        );
        setCurrentVersion(base.documentId(), previousVersionId);
        insertChunkAndEmbedding(
            base.documentId(),
            previousVersionId,
            base.embeddingModelId(),
            "이전 검색 본문",
            0.8f
        );

        Long targetVersionId = insertVersion(
            base.documentId(),
            base.userId(),
            2,
            "EMBEDDING"
        );
        insertChunkAndEmbedding(
            base.documentId(),
            targetVersionId,
            base.embeddingModelId(),
            "새 검색 본문",
            1.0f
        );
        JobContext job = insertProcessingJob(
            targetVersionId,
            base.embeddingModelId(),
            base.workerId()
        );
        return new ExecutionContext(
            base.userId(),
            base.workerId(),
            base.documentId(),
            previousVersionId,
            targetVersionId,
            base.embeddingModelId(),
            job.jobId(),
            job.attemptId()
        );
    }

    private BaseContext insertBase(String documentStatus) {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Completion Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "index-completion-" + suffix + "@example.com");
        Long workerId = jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, status, last_heartbeat_at, started_at,
                created_at, updated_at
            )
            VALUES ('completion-worker', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, suffix);
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, 'Completion Test Document', 'TXT', 'UPLOAD', ?, 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId, documentStatus);
        Long embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
        return new BaseContext(userId, workerId, documentId, embeddingModelId);
    }

    private Long insertVersion(
        Long documentId,
        Long userId,
        int versionNo,
        String status
    ) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                indexed_at, created_by, created_at, updated_at
            )
            VALUES (?, ?, 'Completion Test Version', 'text/plain', ?,
                    CASE WHEN ? = 'INDEXED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, versionNo, status, status, userId);
    }

    private void setCurrentVersion(Long documentId, Long versionId) {
        jdbcTemplate.update(
            "UPDATE documents SET current_version_id = ? WHERE id = ?",
            versionId,
            documentId
        );
    }

    private void insertChunkAndEmbedding(
        Long documentId,
        Long versionId,
        Long embeddingModelId,
        String chunkText,
        float firstVectorValue
    ) {
        Long chunkId = jdbcTemplate.queryForObject("""
            INSERT INTO document_chunks (
                document_version_id, chunk_index, chunk_text, token_count,
                char_start, char_end, content_hash, created_at, updated_at
            )
            VALUES (?, 0, ?, 3, 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, chunkText, chunkText.length(), CONTENT_HASH);
        jdbcTemplate.update("""
            INSERT INTO embeddings (
                chunk_id, document_id, document_version_id, embedding_model_id,
                vector, dimension, vector_hash, status, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            chunkId,
            documentId,
            versionId,
            embeddingModelId,
            vector(firstVectorValue),
            VECTOR_DIMENSION,
            CONTENT_HASH
        );
    }

    private JobContext insertProcessingJob(
        Long versionId,
        Long embeddingModelId,
        Long workerId
    ) {
        Long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, locked_by_worker_id, locked_at, lock_expires_at,
                claim_token, started_at, created_at, updated_at
            )
            VALUES (?, ?, 'PROCESSING', 0, 0, 3, ?, CURRENT_TIMESTAMP,
                    TIMESTAMP '2099-01-01 00:00:00', ?,
                    CURRENT_TIMESTAMP - INTERVAL '5 seconds',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, embeddingModelId, workerId, CLAIM_TOKEN);
        Long attemptId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_job_attempts (
                embedding_job_id, worker_node_id, attempt_no, claim_token, status,
                started_at, created_at, updated_at
            )
            VALUES (?, ?, 1, ?, 'STARTED', CURRENT_TIMESTAMP - INTERVAL '5 seconds',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, jobId, workerId, CLAIM_TOKEN);
        return new JobContext(jobId, attemptId);
    }

    private List<VectorSearchCandidate> search(ExecutionContext context) {
        float[] queryVector = new float[VECTOR_DIMENSION];
        queryVector[0] = 1.0f;
        return vectorSearchQueryService.search(
            queryVector,
            context.embeddingModelId(),
            List.of(context.documentId()),
            5
        );
    }

    private DocumentIndexingCompletionResponse completeAfterBarrier(
        ExecutionContext context,
        CompleteDocumentIndexingRequest request,
        CyclicBarrier startBarrier
    ) throws Exception {
        startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return completionService.complete(context.jobId(), context.attemptId(), request);
    }

    private void installFailingIndexedEventTrigger() {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION fail_indexed_event_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                IF NEW.event_type = 'INDEXED' THEN
                    RAISE EXCEPTION 'forced indexed event failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbcTemplate.execute("""
            CREATE TRIGGER trg_fail_indexed_event_insert
            BEFORE INSERT ON indexing_events
            FOR EACH ROW
            EXECUTE FUNCTION fail_indexed_event_insert()
            """);
    }

    private void removeFailingIndexedEventTrigger() {
        jdbcTemplate.execute("""
            DROP TRIGGER IF EXISTS trg_fail_indexed_event_insert ON indexing_events
            """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_indexed_event_insert()");
    }

    private String vector(float firstValue) {
        return "[" + firstValue + "," + "0,".repeat(VECTOR_DIMENSION - 2) + "0]";
    }

    private String queryString(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }

    private Long queryLong(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, Long.class, id);
    }

    private int indexedEventCount(Long jobId) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM indexing_events
            WHERE embedding_job_id = ? AND event_type = 'INDEXED'
            """, Integer.class, jobId);
    }

    private boolean completionTimestampsMatch(ExecutionContext context) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
            SELECT job.completed_at = attempt.ended_at
               AND job.completed_at = version.indexed_at
               AND job.completed_at = event.occurred_at
            FROM embedding_jobs job
            JOIN embedding_job_attempts attempt ON attempt.embedding_job_id = job.id
            JOIN document_versions version ON version.id = job.document_version_id
            JOIN indexing_events event
              ON event.embedding_job_id = job.id AND event.event_type = 'INDEXED'
            WHERE job.id = ?
            """, Boolean.class, context.jobId()));
    }

    /**
     * 공통 사용자·Worker·Document와 검색 Model 식별자를 묶는다.
     */
    private record BaseContext(
        Long userId,
        Long workerId,
        Long documentId,
        Long embeddingModelId
    ) {
    }

    /**
     * 완료 대상 Job과 Attempt 식별자를 묶는다.
     */
    private record JobContext(Long jobId, Long attemptId) {
    }

    /**
     * 완료 호출과 검색 전후 검증에 필요한 실행·문서·Version 식별자를 묶는다.
     */
    private record ExecutionContext(
        Long userId,
        Long workerId,
        Long documentId,
        Long previousVersionId,
        Long targetVersionId,
        Long embeddingModelId,
        Long jobId,
        Long attemptId
    ) {
    }
}
