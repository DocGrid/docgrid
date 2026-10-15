package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.embedding.dto.response.ManualRetriedIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobManualRetryService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import com.opensource.docgrid.global.support.TruncateDeadlockRetry;

/**
 * 실제 PostgreSQL에서 최종 실패 Job 수동 재처리의 상태 전이, 검색 보호와 동시 요청 수렴을 검증한다.
 *
 * <p>격리 Schema에 최종 실패로 종결된 실행 상태를 직접 구성한 뒤 실제 Service Transaction과 행 잠금을
 * 사용해, Queue 복귀가 부분 상태 없이 원자 커밋되고 현재 검색 가능한 이전 Version이 그대로 유지되는지
 * 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding Job 수동 재처리 PostgreSQL 통합 테스트")
class EmbeddingJobManualRetryIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_manual_retry_integration_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final int CONCURRENT_REQUESTS = 2;
    private static final long TIMEOUT_SECONDS = 10;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EmbeddingJobRepository embeddingJobRepository;
    @Autowired private EmbeddingJobManualRetryService manualRetryService;
    @Autowired private VectorSearchQueryService vectorSearchQueryService;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-manual-retry-integration-test-secret-key-2026");
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
    @DisplayName("최종 실패 Job은 소유권을 비우고 즉시 Claim 가능한 PENDING으로 복귀한다")
    void manualRetry_requeuesTerminalFailedJobWithoutOwnership() {
        ExecutionContext context = insertTerminalFailedExecution(true);

        ManualRetriedIndexingJobResponse response = manualRetryService.retry(context.jobId());

        assertThat(response.documentVersionStatus().name()).isEqualTo("CHUNKED");
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("PENDING");
        assertThat(queryLong(
            "SELECT locked_by_worker_id FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isNull();
        assertThat(queryString("SELECT claim_token FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isNull();
        assertThat(queryDateTime(
            "SELECT lock_expires_at FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isNull();
        assertThat(queryDateTime("SELECT failed_at FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isNull();
        assertThat(queryDateTime(
            "SELECT next_retry_at FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isNull();
        // 지연 없이 다음 Claim 후보가 되어야 Worker Polling이 곧바로 재처리를 시작할 수 있다.
        assertThat(findPendingJobAt(LocalDateTime.now())).isEqualTo(context.jobId());
    }

    @Test
    @DisplayName("이미 저장된 Chunk는 유지하고 STALE Embedding만 삭제해 임베딩 단계부터 재개한다")
    void manualRetry_keepsChunksAndDeletesStaleEmbeddings() {
        ExecutionContext context = insertTerminalFailedExecution(true);

        manualRetryService.retry(context.jobId());

        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            context.targetVersionId()
        )).isEqualTo("CHUNKED");
        assertThat(countBy(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            context.targetVersionId()
        )).isOne();
        assertThat(countBy(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            context.targetVersionId()
        )).isZero();
        assertThat(queryString("SELECT status FROM documents WHERE id = ?", context.documentId()))
            .isEqualTo("INDEXING");
    }

    @Test
    @DisplayName("Chunk가 없는 최종 실패 Job은 파싱부터 다시 시작하도록 UPLOADED로 재개한다")
    void manualRetry_resumesFromUploaded_when_chunksDoNotExist() {
        ExecutionContext context = insertTerminalFailedExecution(false);

        ManualRetriedIndexingJobResponse response = manualRetryService.retry(context.jobId());

        assertThat(response.documentVersionStatus().name()).isEqualTo("UPLOADED");
        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            context.targetVersionId()
        )).isEqualTo("UPLOADED");
    }

    @Test
    @DisplayName("재처리는 기존 Attempt 이력과 재시도 횟수를 그대로 두고 MANUAL_RETRY 이벤트만 추가한다")
    void manualRetry_preservesAuditHistory() {
        ExecutionContext context = insertTerminalFailedExecution(true);

        manualRetryService.retry(context.jobId());

        assertThat(queryInteger(
            "SELECT retry_count FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isEqualTo(3);
        assertThat(queryString(
            "SELECT status FROM embedding_job_attempts WHERE id = ?",
            context.attemptId()
        )).isEqualTo("FAILED");
        assertThat(countBy(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ?",
            context.jobId()
        )).isOne();
        assertThat(eventCount(context.jobId(), "FAILED")).isOne();
        assertThat(eventCount(context.jobId(), "MANUAL_RETRY")).isOne();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT metadata_json
            FROM indexing_events
            WHERE embedding_job_id = ? AND event_type = 'MANUAL_RETRY'
            """, String.class, context.jobId()))
            .contains("\"resumeVersionStatus\":\"CHUNKED\"", "\"retryCount\":3")
            .doesNotContain(CLAIM_TOKEN);
    }

    @Test
    @DisplayName("이전 INDEXED Version이 검색 중이면 재처리 후에도 검색 결과와 현재 포인터를 보존한다")
    void manualRetry_preservesPreviousSearchableVersion() {
        ExecutionContext context = insertReplacementVersionFailedExecution();
        assertThat(search(context))
            .extracting(VectorSearchCandidate::chunkText)
            .containsExactly("이전 검색 본문");

        manualRetryService.retry(context.jobId());

        assertThat(queryString("SELECT status FROM documents WHERE id = ?", context.documentId()))
            .isEqualTo("INDEXED");
        assertThat(queryLong(
            "SELECT current_version_id FROM documents WHERE id = ?",
            context.documentId()
        )).isEqualTo(context.previousVersionId());
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.previousVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(search(context))
            .extracting(VectorSearchCandidate::chunkText)
            .containsExactly("이전 검색 본문");
    }

    @Test
    @DisplayName("동시 수동 재처리 요청은 하나의 상태 전이로 수렴하고 나머지는 충돌로 거부된다")
    void manualRetryConcurrently_commitsSingleTransition() throws Exception {
        ExecutionContext context = insertTerminalFailedExecution(true);
        CyclicBarrier startBarrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        List<RetryOutcome> outcomes;
        try {
            List<Future<RetryOutcome>> futures = List.of(
                executor.submit(() -> retryAfterBarrier(context, startBarrier)),
                executor.submit(() -> retryAfterBarrier(context, startBarrier))
            );
            outcomes = List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(outcomes).filteredOn(RetryOutcome::committed).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> !outcome.committed())
            .extracting(RetryOutcome::errorCode)
            .containsExactly(ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED.name());
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("PENDING");
        assertThat(eventCount(context.jobId(), "MANUAL_RETRY")).isOne();
        assertThat(countBy(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            context.targetVersionId()
        )).isZero();
    }

    @Test
    @DisplayName("자동 재시도가 예정된 PENDING Job은 수동 재처리를 거부하고 예약을 유지한다")
    void manualRetry_rejectsRetryScheduledJob() {
        ExecutionContext context = insertTerminalFailedExecution(true);
        jdbcTemplate.update("""
            UPDATE embedding_jobs
            SET status = 'PENDING',
                failed_at = NULL,
                next_retry_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
            WHERE id = ?
            """, context.jobId());

        assertThatThrownBy(() -> manualRetryService.retry(context.jobId()))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_NOT_ALLOWED
            );
        assertThat(queryDateTime(
            "SELECT next_retry_at FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isNotNull();
        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            context.targetVersionId()
        )).isEqualTo("FAILED");
        assertThat(eventCount(context.jobId(), "MANUAL_RETRY")).isZero();
    }

    @Test
    @DisplayName("더 새로운 Version이 올라온 뒤에는 과거 Version 재처리를 거부한다")
    void manualRetry_rejectsStaleVersionTarget() {
        ExecutionContext context = insertTerminalFailedExecution(true);
        insertVersion(context.documentId(), context.userId(), 2, "UPLOADED");

        assertThatThrownBy(() -> manualRetryService.retry(context.jobId()))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue(
                "errorCode",
                ErrorCode.EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID
            );
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("FAILED");
        assertThat(countBy(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?",
            context.targetVersionId()
        )).isOne();
    }

    private ExecutionContext insertTerminalFailedExecution(boolean withChunk) {
        BaseContext base = insertBase("FAILED");
        Long versionId = insertVersion(base.documentId(), base.userId(), 1, "FAILED");
        setCurrentVersion(base.documentId(), versionId);
        if (withChunk) {
            insertChunkAndEmbedding(
                base.documentId(),
                versionId,
                base.embeddingModelId(),
                "실패 대상 본문",
                1.0f,
                "STALE"
            );
        }
        JobContext job = insertFailedJob(versionId, base.embeddingModelId(), base.workerId());
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

    private ExecutionContext insertReplacementVersionFailedExecution() {
        BaseContext base = insertBase("INDEXED");
        Long previousVersionId = insertVersion(base.documentId(), base.userId(), 1, "INDEXED");
        setCurrentVersion(base.documentId(), previousVersionId);
        insertChunkAndEmbedding(
            base.documentId(),
            previousVersionId,
            base.embeddingModelId(),
            "이전 검색 본문",
            0.8f,
            "ACTIVE"
        );

        Long targetVersionId = insertVersion(base.documentId(), base.userId(), 2, "FAILED");
        insertChunkAndEmbedding(
            base.documentId(),
            targetVersionId,
            base.embeddingModelId(),
            "실패 대상 본문",
            1.0f,
            "STALE"
        );
        JobContext job = insertFailedJob(targetVersionId, base.embeddingModelId(), base.workerId());
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
            VALUES (?, 'password-hash', 'Manual Retry Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "manual-retry-" + suffix + "@example.com");
        Long workerId = jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, status, last_heartbeat_at, started_at,
                created_at, updated_at
            )
            VALUES ('manual-retry-worker', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, suffix);
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, 'Manual Retry Test Document', 'TXT', 'UPLOAD', ?, 'PRIVATE',
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

    private Long insertVersion(Long documentId, Long userId, int versionNo, String status) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                indexed_at, created_by, created_at, updated_at
            )
            VALUES (?, ?, 'Manual Retry Test Version', 'text/plain', ?,
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
        float firstVectorValue,
        String embeddingStatus
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
            VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            chunkId,
            documentId,
            versionId,
            embeddingModelId,
            vector(firstVectorValue),
            VECTOR_DIMENSION,
            CONTENT_HASH,
            embeddingStatus
        );
    }

    /**
     * 자동 재시도를 모두 소진하고 소유권 정보가 남아 있는 최종 실패 Job과 FAILED Attempt를 만든다.
     */
    private JobContext insertFailedJob(Long versionId, Long embeddingModelId, Long workerId) {
        Long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, locked_by_worker_id, locked_at, lock_expires_at,
                claim_token, started_at, failed_at, error_code, error_message,
                created_at, updated_at
            )
            VALUES (?, ?, 'FAILED', 0, 3, 3, ?, CURRENT_TIMESTAMP - INTERVAL '10 seconds',
                    CURRENT_TIMESTAMP - INTERVAL '5 seconds', ?,
                    CURRENT_TIMESTAMP - INTERVAL '30 seconds',
                    CURRENT_TIMESTAMP - INTERVAL '5 seconds',
                    'EMBEDDING_PROVIDER_UNAVAILABLE', 'Provider timeout',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, embeddingModelId, workerId, CLAIM_TOKEN);
        Long attemptId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_job_attempts (
                embedding_job_id, worker_node_id, attempt_no, claim_token, status,
                started_at, ended_at, duration_ms, error_code, error_message,
                created_at, updated_at
            )
            VALUES (?, ?, 1, ?, 'FAILED', CURRENT_TIMESTAMP - INTERVAL '30 seconds',
                    CURRENT_TIMESTAMP - INTERVAL '5 seconds', 25000,
                    'EMBEDDING_PROVIDER_UNAVAILABLE', 'Provider timeout',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, jobId, workerId, CLAIM_TOKEN);
        jdbcTemplate.update("""
            INSERT INTO indexing_events (
                embedding_job_id, event_type, from_status, to_status, message,
                occurred_at, created_at, updated_at
            )
            VALUES (?, 'FAILED', 'PROCESSING', 'FAILED',
                    'Embedding Job을 최종 실패로 종료했습니다.',
                    CURRENT_TIMESTAMP - INTERVAL '5 seconds',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, jobId);
        return new JobContext(jobId, attemptId);
    }

    private RetryOutcome retryAfterBarrier(
        ExecutionContext context,
        CyclicBarrier startBarrier
    ) throws Exception {
        startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            manualRetryService.retry(context.jobId());
            return new RetryOutcome(true, null);
        } catch (DocGridException exception) {
            return new RetryOutcome(false, exception.getErrorCode().name());
        }
    }

    private Long findPendingJobAt(LocalDateTime claimedAt) {
        return transactionTemplate.execute(status -> embeddingJobRepository
            .findNextPendingForUpdate(claimedAt)
            .map(EmbeddingJob::getId)
            .orElse(null));
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

    private String vector(float firstValue) {
        return "[" + firstValue + "," + "0,".repeat(VECTOR_DIMENSION - 2) + "0]";
    }

    private String queryString(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }

    private Long queryLong(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, Long.class, id);
    }

    private Integer queryInteger(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, Integer.class, id);
    }

    private LocalDateTime queryDateTime(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, LocalDateTime.class, id);
    }

    private Integer countBy(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, Integer.class, id);
    }

    private int eventCount(Long jobId, String eventType) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM indexing_events
            WHERE embedding_job_id = ? AND event_type = ?
            """, Integer.class, jobId, eventType);
    }

    /**
     * 한 테스트가 사용하는 사용자, Worker, 문서와 임베딩 모델 식별자 묶음.
     */
    private record BaseContext(Long userId, Long workerId, Long documentId, Long embeddingModelId) {
    }

    /**
     * 최종 실패로 종결된 Job과 그 Attempt 식별자 묶음.
     */
    private record JobContext(Long jobId, Long attemptId) {
    }

    /**
     * 수동 재처리 검증에 필요한 전체 실행 Context 식별자 묶음.
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

    /**
     * 동시 재처리 요청 한 건의 커밋 여부와 거부 사유 코드.
     */
    private record RetryOutcome(boolean committed, String errorCode) {
    }
}
