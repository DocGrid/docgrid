package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

import com.opensource.docgrid.domain.embedding.dto.request.CompleteDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.request.FailDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.response.DocumentIndexingFailureResponse;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingJob;
import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingCompletionService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingFailureService;
import com.opensource.docgrid.domain.search.dto.VectorSearchCandidate;
import com.opensource.docgrid.domain.search.service.query.VectorSearchQueryService;
import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.global.exception.DocGridException;

/**
 * 실제 PostgreSQL에서 인덱싱 실패의 예약 Queue, 최종 검색 상태, 멱등성과 완료 경쟁을 검증한다.
 *
 * <p>격리 Schema에 각 실행 상태를 직접 구성한 뒤 실제 Service Transaction과 PostgreSQL 행 잠금을
 * 사용해 Retry 또는 최종 실패가 부분 상태 없이 원자 커밋되는지 확인한다. 여러 Job의 Provider
 * 경계 Retry는 실제 저장된 예약 시각에서 지연 분포를 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Document 인덱싱 실패 PostgreSQL 통합 테스트")
class DocumentIndexingFailureIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_index_failure_integration_test";
    private static final int VECTOR_DIMENSION = 1024;
    private static final int CONCURRENT_REQUESTS = 2;
    private static final long TIMEOUT_SECONDS = 10;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EmbeddingJobRepository embeddingJobRepository;
    @Autowired private DocumentIndexingFailureService failureService;
    @Autowired private DocumentIndexingCompletionService completionService;
    @Autowired private VectorSearchQueryService vectorSearchQueryService;
    @Autowired private IndexingWorkerProperties workerProperties;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-index-failure-integration-test-secret-key-2026");
        registry.add("indexing.worker.retry-initial-delay", () -> "10s");
        registry.add("indexing.worker.retry-max-delay", () -> "5m");
    }

    @BeforeEach
    void resetState() {
        jdbcTemplate.execute("""
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
    @DisplayName("Retry 예약 전 Job은 선택되지 않고 정확한 예약 시각부터 Queue 후보가 된다")
    void retryJob_becomesClaimableAtScheduledTime() {
        ExecutionContext context = insertFirstVersionExecution("PARSING", false);

        DocumentIndexingFailureResponse response = failureService.fail(
            context.jobId(),
            context.attemptId(),
            failureRequest(context.workerId(), IndexingFailureType.STORAGE_UNAVAILABLE, "Storage timeout")
        );

        LocalDateTime nextRetryAt = queryDateTime(
            "SELECT next_retry_at FROM embedding_jobs WHERE id = ?",
            context.jobId()
        );
        assertThat(Duration.between(response.failedAt(), nextRetryAt)).isEqualTo(Duration.ofSeconds(10));
        assertThat(findPendingJobAt(nextRetryAt.minusNanos(1_000))).isNull();
        assertThat(findPendingJobAt(nextRetryAt)).isEqualTo(context.jobId());
        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("PENDING");
        assertThat(queryInteger(
            "SELECT retry_count FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isOne();
        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            context.targetVersionId()
        )).isEqualTo("PARSING");
        assertThat(eventCount(context.jobId(), "PARSE_FAILED")).isOne();
        assertThat(eventCount(context.jobId(), "RETRY")).isOne();
    }

    @Test
    @DisplayName("Provider Retry-After 전에는 과부하 Job을 Queue 후보로 선택하지 않는다")
    void overloadedJob_respectsProviderMinimumRetryDelay() {
        ExecutionContext context = insertFirstVersionExecution("EMBEDDING", false);

        DocumentIndexingFailureResponse response = failureService.fail(
            context.jobId(),
            context.attemptId(),
            failureRequest(
                context.workerId(),
                IndexingFailureType.EMBEDDING_PROVIDER_OVERLOADED,
                "Embedding provider overloaded"
            ),
            Duration.ofSeconds(15)
        );

        LocalDateTime nextRetryAt = queryDateTime(
            "SELECT next_retry_at FROM embedding_jobs WHERE id = ?",
            context.jobId()
        );
        assertThat(Duration.between(response.failedAt(), nextRetryAt))
            .isEqualTo(Duration.ofSeconds(15));
        assertThat(findPendingJobAt(nextRetryAt.minusNanos(1_000))).isNull();
        assertThat(findPendingJobAt(nextRetryAt)).isEqualTo(context.jobId());
        assertThat(queryInteger(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ?",
            context.jobId()
        )).isOne();
    }

    @Test
    @DisplayName("Provider 상한 직전 하한을 받은 여러 Job의 저장 지연을 1초 구간에 분산한다")
    void overloadedJobs_distributePersistedRetryDelaysAcrossPollingWindows() {
        Duration minimumRetryDelay = Duration.ofMillis(11_950);
        double originalJitterRatio = workerProperties.getRetryJitterRatio();
        List<Duration> storedDelays = new ArrayList<>();
        workerProperties.setRetryJitterRatio(0.2);

        try {
            // 1. 동일한 Provider 하한으로 40개 Job을 실제 실패 처리하고 DB 예약 시각을 읽는다.
            for (int index = 0; index < 40; index++) {
                ExecutionContext context = insertFirstVersionExecution("EMBEDDING", false);
                DocumentIndexingFailureResponse response = failureService.fail(
                    context.jobId(),
                    context.attemptId(),
                    failureRequest(
                        context.workerId(),
                        IndexingFailureType.EMBEDDING_PROVIDER_OVERLOADED,
                        "Embedding provider overloaded"
                    ),
                    minimumRetryDelay
                );
                LocalDateTime nextRetryAt = queryDateTime(
                    "SELECT next_retry_at FROM embedding_jobs WHERE id = ?",
                    context.jobId()
                );
                Duration storedDelay = Duration.between(response.failedAt(), nextRetryAt);
                assertThat(storedDelay).isBetween(minimumRetryDelay, Duration.ofMillis(13_950));
                storedDelays.add(storedDelay);
            }

            // 2. 실패 시각에 정렬된 1초 조회 모델에서 저장된 예약 지연의 분포를 기록한다.
            long tick12 = storedDelays.stream().filter(delay -> firstAlignedPollTick(delay) == 12).count();
            long tick13 = storedDelays.stream().filter(delay -> firstAlignedPollTick(delay) == 13).count();
            long tick14 = storedDelays.stream().filter(delay -> firstAlignedPollTick(delay) == 14).count();
            long distinct = storedDelays.stream().distinct().count();
            assertThat(tick12 + tick13 + tick14).isEqualTo(40L);
            assertThat(distinct).isGreaterThan(1L);
            System.out.printf(
                "JITTER_METRIC jobs=40 distinct=%d tick12=%d tick13=%d tick14=%d provider_violations=0%n",
                distinct, tick12, tick13, tick14
            );
        } finally {
            workerProperties.setRetryJitterRatio(originalJitterRatio);
        }
    }

    @Test
    @DisplayName("상한 +1ns Provider 하한은 PostgreSQL에 저장한 뒤에도 지킨다")
    void overloadedJob_preservesNanosecondProviderMinimumAfterPersistence() {
        Duration minimumRetryDelay = Duration.ofSeconds(12).plusNanos(1);
        double originalJitterRatio = workerProperties.getRetryJitterRatio();
        workerProperties.setRetryJitterRatio(0.2);

        try {
            // 1. 밀리초 경계를 넘는 Provider 하한으로 실제 Job을 실패 처리한다.
            ExecutionContext context = insertFirstVersionExecution("EMBEDDING", false);
            DocumentIndexingFailureResponse response = failureService.fail(
                context.jobId(),
                context.attemptId(),
                failureRequest(
                    context.workerId(),
                    IndexingFailureType.EMBEDDING_PROVIDER_OVERLOADED,
                    "Embedding provider overloaded"
                ),
                minimumRetryDelay
            );

            // 2. DB에서 다시 읽은 예약 시각이 원래 나노초 하한보다 이르지 않은지 확인한다.
            LocalDateTime nextRetryAt = queryDateTime(
                "SELECT next_retry_at FROM embedding_jobs WHERE id = ?",
                context.jobId()
            );
            assertThat(Duration.between(response.failedAt(), nextRetryAt))
                .isGreaterThanOrEqualTo(minimumRetryDelay);
        } finally {
            workerProperties.setRetryJitterRatio(originalJitterRatio);
        }
    }

    @Test
    @DisplayName("같은 실패 요청 두 건은 단일 Retry와 동일한 Attempt 응답으로 수렴한다")
    void failConcurrently_isIdempotent() throws Exception {
        ExecutionContext context = insertFirstVersionExecution("PARSING", false);
        FailDocumentIndexingRequest request = failureRequest(
            context.workerId(),
            IndexingFailureType.WORKER_INTERNAL_ERROR,
            "temporary worker failure"
        );
        CyclicBarrier startBarrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        List<DocumentIndexingFailureResponse> responses;
        try {
            List<Future<DocumentIndexingFailureResponse>> futures = List.of(
                executor.submit(() -> failAfterBarrier(context, request, startBarrier)),
                executor.submit(() -> failAfterBarrier(context, request, startBarrier))
            );
            responses = List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(responses.get(1)).isEqualTo(responses.get(0));
        assertThat(queryInteger(
            "SELECT retry_count FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isOne();
        assertThat(eventCount(context.jobId(), "PARSE_FAILED")).isOne();
        assertThat(eventCount(context.jobId(), "RETRY")).isOne();
        assertThat(queryString(
            "SELECT status FROM embedding_job_attempts WHERE id = ?",
            context.attemptId()
        )).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("새 Version의 영구 실패는 이전 INDEXED 검색 Set을 그대로 보존한다")
    void terminalFailure_preservesPreviousSearchableVersion() {
        ExecutionContext context = insertReplacementVersionExecution();

        assertThat(search(context))
            .extracting(VectorSearchCandidate::chunkText)
            .containsExactly("이전 검색 본문");

        failureService.fail(
            context.jobId(),
            context.attemptId(),
            failureRequest(
                context.workerId(),
                IndexingFailureType.EMBEDDING_RESULT_INVALID,
                "Vector dimension mismatch"
            )
        );

        assertThat(queryString("SELECT status FROM documents WHERE id = ?", context.documentId()))
            .isEqualTo("INDEXED");
        assertThat(queryLong(
            "SELECT current_version_id FROM documents WHERE id = ?",
            context.documentId()
        )).isEqualTo(context.previousVersionId());
        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            context.targetVersionId()
        )).isEqualTo("FAILED");
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.previousVersionId()
        )).isEqualTo("ACTIVE");
        assertThat(queryString(
            "SELECT status FROM embeddings WHERE document_version_id = ?",
            context.targetVersionId()
        )).isEqualTo("STALE");
        assertThat(search(context))
            .extracting(VectorSearchCandidate::chunkText)
            .containsExactly("이전 검색 본문");
        assertThat(eventCount(context.jobId(), "EMBEDDING_FAILED")).isOne();
        assertThat(eventCount(context.jobId(), "FAILED")).isOne();
    }

    @Test
    @DisplayName("동일 Attempt의 완료와 실패 동시 요청은 한쪽만 성공하고 일관된 상태로 수렴한다")
    void completeAndFailConcurrently_onlyOneTransitionCommits() throws Exception {
        ExecutionContext context = insertFirstVersionExecution("EMBEDDING", true);
        CyclicBarrier startBarrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        List<RaceOutcome> outcomes;
        try {
            List<Future<RaceOutcome>> futures = List.of(
                executor.submit(() -> completeAfterBarrier(context, startBarrier)),
                executor.submit(() -> failRaceAfterBarrier(context, startBarrier))
            );
            outcomes = List.of(
                futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(outcomes).filteredOn(RaceOutcome::committed).hasSize(1);
        String jobStatus = queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId());
        String attemptStatus = queryString(
            "SELECT status FROM embedding_job_attempts WHERE id = ?",
            context.attemptId()
        );
        if (jobStatus.equals("INDEXED")) {
            assertThat(attemptStatus).isEqualTo("SUCCESS");
            assertThat(eventCount(context.jobId(), "INDEXED")).isOne();
            assertThat(eventCount(context.jobId(), "RETRY")).isZero();
            assertThat(eventCount(context.jobId(), "EMBEDDING_FAILED")).isZero();
        } else {
            assertThat(jobStatus).isEqualTo("PENDING");
            assertThat(attemptStatus).isEqualTo("FAILED");
            assertThat(eventCount(context.jobId(), "INDEXED")).isZero();
            assertThat(eventCount(context.jobId(), "RETRY")).isOne();
            assertThat(eventCount(context.jobId(), "EMBEDDING_FAILED")).isOne();
        }
    }

    @Test
    @DisplayName("RETRY 이벤트 저장 실패 시 Attempt와 Job의 모든 실패 변경을 Rollback한다")
    void fail_rollsBackAllChanges_whenRetryEventInsertFails() {
        ExecutionContext context = insertFirstVersionExecution("PARSING", false);
        installFailingRetryEventTrigger();

        try {
            assertThatThrownBy(() -> failureService.fail(
                context.jobId(),
                context.attemptId(),
                failureRequest(
                    context.workerId(),
                    IndexingFailureType.STORAGE_UNAVAILABLE,
                    "Storage timeout"
                )
            )).isInstanceOf(RuntimeException.class);
        } finally {
            removeFailingRetryEventTrigger();
        }

        assertThat(queryString("SELECT status FROM embedding_jobs WHERE id = ?", context.jobId()))
            .isEqualTo("PROCESSING");
        assertThat(queryInteger(
            "SELECT retry_count FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isZero();
        assertThat(queryDateTime(
            "SELECT next_retry_at FROM embedding_jobs WHERE id = ?",
            context.jobId()
        )).isNull();
        assertThat(queryString(
            "SELECT status FROM embedding_job_attempts WHERE id = ?",
            context.attemptId()
        )).isEqualTo("STARTED");
        assertThat(queryString(
            "SELECT status FROM document_versions WHERE id = ?",
            context.targetVersionId()
        )).isEqualTo("PARSING");
        assertThat(eventCount(context.jobId(), "PARSE_FAILED")).isZero();
        assertThat(eventCount(context.jobId(), "RETRY")).isZero();
    }

    private ExecutionContext insertFirstVersionExecution(
        String versionStatus,
        boolean withEmbedding
    ) {
        BaseContext base = insertBase("UPLOADED");
        Long versionId = insertVersion(base.documentId(), base.userId(), 1, versionStatus);
        setCurrentVersion(base.documentId(), versionId);
        if (withEmbedding) {
            insertChunkAndEmbedding(
                base.documentId(),
                versionId,
                base.embeddingModelId(),
                "완료 경쟁 본문",
                1.0f
            );
        }
        JobContext job = insertProcessingJob(versionId, base.embeddingModelId(), base.workerId());
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
        Long previousVersionId = insertVersion(base.documentId(), base.userId(), 1, "INDEXED");
        setCurrentVersion(base.documentId(), previousVersionId);
        insertChunkAndEmbedding(
            base.documentId(),
            previousVersionId,
            base.embeddingModelId(),
            "이전 검색 본문",
            0.8f
        );

        Long targetVersionId = insertVersion(base.documentId(), base.userId(), 2, "EMBEDDING");
        insertChunkAndEmbedding(
            base.documentId(),
            targetVersionId,
            base.embeddingModelId(),
            "실패 대상 본문",
            1.0f
        );
        JobContext job = insertProcessingJob(targetVersionId, base.embeddingModelId(), base.workerId());
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
            VALUES (?, 'password-hash', 'Failure Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "index-failure-" + suffix + "@example.com");
        Long workerId = jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, status, last_heartbeat_at, started_at,
                created_at, updated_at
            )
            VALUES ('failure-worker', ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, suffix);
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility,
                created_at, updated_at
            )
            VALUES (?, 'Failure Test Document', 'TXT', 'UPLOAD', ?, 'PRIVATE',
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
            VALUES (?, ?, 'Failure Test Version', 'text/plain', ?,
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

    private FailDocumentIndexingRequest failureRequest(
        Long workerId,
        IndexingFailureType failureType,
        String errorMessage
    ) {
        return new FailDocumentIndexingRequest(
            workerId,
            CLAIM_TOKEN,
            failureType,
            errorMessage
        );
    }

    private Long findPendingJobAt(LocalDateTime claimedAt) {
        return transactionTemplate.execute(status -> embeddingJobRepository
            .findNextPendingForUpdate(claimedAt)
            .map(EmbeddingJob::getId)
            .orElse(null));
    }

    /**
     * 실패 시각과 조회 시각이 정렬됐다고 가정한 1초 Polling 모델의 첫 실행 가능 구간을 반환한다.
     */
    private long firstAlignedPollTick(Duration delay) {
        long oneSecondNanos = Duration.ofSeconds(1).toNanos();
        return (delay.toNanos() + oneSecondNanos - 1) / oneSecondNanos;
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

    private DocumentIndexingFailureResponse failAfterBarrier(
        ExecutionContext context,
        FailDocumentIndexingRequest request,
        CyclicBarrier startBarrier
    ) throws Exception {
        startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return failureService.fail(context.jobId(), context.attemptId(), request);
    }

    private RaceOutcome completeAfterBarrier(
        ExecutionContext context,
        CyclicBarrier startBarrier
    ) throws Exception {
        startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            completionService.complete(
                context.jobId(),
                context.attemptId(),
                new CompleteDocumentIndexingRequest(context.workerId(), CLAIM_TOKEN)
            );
            return new RaceOutcome(true, "INDEXED");
        } catch (DocGridException exception) {
            return new RaceOutcome(false, exception.getErrorCode().name());
        }
    }

    private RaceOutcome failRaceAfterBarrier(
        ExecutionContext context,
        CyclicBarrier startBarrier
    ) throws Exception {
        startBarrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            failureService.fail(
                context.jobId(),
                context.attemptId(),
                failureRequest(
                    context.workerId(),
                    IndexingFailureType.EMBEDDING_PROVIDER_UNAVAILABLE,
                    "Provider timeout"
                )
            );
            return new RaceOutcome(true, "PENDING");
        } catch (DocGridException exception) {
            return new RaceOutcome(false, exception.getErrorCode().name());
        }
    }

    private void installFailingRetryEventTrigger() {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION fail_retry_event_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                IF NEW.event_type = 'RETRY' THEN
                    RAISE EXCEPTION 'forced retry event failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbcTemplate.execute("""
            CREATE TRIGGER trg_fail_retry_event_insert
            BEFORE INSERT ON indexing_events
            FOR EACH ROW
            EXECUTE FUNCTION fail_retry_event_insert()
            """);
    }

    private void removeFailingRetryEventTrigger() {
        jdbcTemplate.execute("""
            DROP TRIGGER IF EXISTS trg_fail_retry_event_insert ON indexing_events
            """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_retry_event_insert()");
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

    private int eventCount(Long jobId, String eventType) {
        return jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM indexing_events
            WHERE embedding_job_id = ? AND event_type = ?
            """, Integer.class, jobId, eventType);
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
     * 실패 대상 Job과 Attempt 식별자를 묶는다.
     */
    private record JobContext(Long jobId, Long attemptId) {
    }

    /**
     * 실패 호출과 검색 전후 검증에 필요한 실행·문서·Version 식별자를 묶는다.
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
     * 완료와 실패 경쟁 요청 하나가 커밋됐는지 또는 어떤 오류로 거부됐는지 전달한다.
     */
    private record RaceOutcome(boolean committed, String result) {
    }
}
