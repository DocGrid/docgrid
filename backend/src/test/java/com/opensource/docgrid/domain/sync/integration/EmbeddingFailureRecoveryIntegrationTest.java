package com.opensource.docgrid.domain.sync.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.opensource.docgrid.domain.embedding.dto.request.CompleteDocumentIndexingRequest;
import com.opensource.docgrid.domain.embedding.dto.request.StartEmbeddingJobAttemptRequest;
import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.dto.response.StartedEmbeddingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.DocumentEmbeddingDraft;
import com.opensource.docgrid.domain.embedding.service.EmbeddingVectorSupport;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentEmbeddingTransactionService.EmbeddingWork;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobLeaseRecoveryService;
import com.opensource.docgrid.domain.embedding.service.command.DocumentIndexingCompletionService;
import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.service.SyncReconciliationOrchestrator;
import com.opensource.docgrid.global.support.TruncateDeadlockRetry;

/**
 * Embedding Set 저장 중 프로세스 중단을 주입하고 Worker Lease Recovery부터 최종 정합성까지 검증한다.
 *
 * <p>두 번째 Vector INSERT에서 PostgreSQL 예외를 발생시켜 부분 저장을 차단하고, 만료 Claim 회수,
 * 새 Attempt 재실행, 인덱싱 완료와 Reconciler 무결성 확인을 하나의 장애 시나리오로 연결한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding 저장 장애·Lease·Reconciler 복구 통합 테스트")
class EmbeddingFailureRecoveryIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_embedding_failure_recovery_test";
    private static final String EMBEDDING_TRIGGER = "docgrid_test_fail_second_embedding";
    private static final String EMBEDDING_FUNCTION = "docgrid_test_raise_second_embedding_failure";
    private static final int VECTOR_DIMENSION = 1024;
    private static final String FIRST_CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String CONTENT_HASH =
        "26e4a23eec4241e034f1b4631f0222f1895847637c35e77687d5945f75edb42c";

    @Autowired private DocumentEmbeddingTransactionService documentEmbeddingTransactionService;
    @Autowired private EmbeddingJobLeaseRecoveryService embeddingJobLeaseRecoveryService;
    @Autowired private EmbeddingJobClaimService embeddingJobClaimService;
    @Autowired private EmbeddingJobAttemptService embeddingJobAttemptService;
    @Autowired private DocumentIndexingCompletionService documentIndexingCompletionService;
    @Autowired private SyncReconciliationOrchestrator syncReconciliationOrchestrator;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureSchema(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-embedding-failure-recovery-secret-key-2026");
        registry.add("indexing.worker.retry-initial-delay", () -> "1ms");
        registry.add("indexing.worker.retry-max-delay", () -> "1ms");
    }

    @BeforeEach
    void resetState() {
        dropEmbeddingFailureTrigger();
        TruncateDeadlockRetry.execute(jdbcTemplate, """
            TRUNCATE TABLE
                sync_admin_actions,
                sync_consistency_issues,
                sync_reconciliation_runs,
                sync_event_delivery_attempts,
                embeddings,
                indexing_events,
                document_chunks,
                embedding_job_attempts,
                embedding_jobs,
                sync_outbox_events,
                document_versions,
                documents,
                worker_nodes
            RESTART IDENTITY CASCADE
            """);
    }

    @AfterEach
    void dropTriggerAfterTest() {
        dropEmbeddingFailureTrigger();
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Embedding 저장 도중 장애는 부분 Vector 없이 Lease 회수·재실행·Reconciliation으로 정상화된다")
    void failureDuringEmbeddingSave_recoversToConsistentIndexedState() {
        ExecutionContext context = insertExecution();
        EmbeddingWork firstWork = documentEmbeddingTransactionService.prepare(
            context.jobId(),
            context.attemptId(),
            context.workerId(),
            FIRST_CLAIM_TOKEN
        ).work();
        List<DocumentEmbeddingDraft> firstDrafts = drafts(firstWork);
        installSecondEmbeddingFailure();

        // 1. 두 번째 Vector INSERT를 실패시켜 saveAllAndFlush Transaction 전체를 Rollback한다.
        assertThatThrownBy(() -> documentEmbeddingTransactionService.complete(
            context.jobId(),
            context.attemptId(),
            context.workerId(),
            FIRST_CLAIM_TOKEN,
            firstWork,
            firstDrafts
        )).isInstanceOf(DataAccessException.class);
        assertThat(embeddingCount(context.versionId())).isZero();
        assertThat(queryStatus("document_versions", context.versionId())).isEqualTo("EMBEDDING");
        assertThat(queryStatus("embedding_jobs", context.jobId())).isEqualTo("PROCESSING");

        // 2. 프로세스 중단으로 실패 응답도 유실된 상태에서 Worker Lease가 Attempt와 Job을 회수한다.
        dropEmbeddingFailureTrigger();
        LocalDateTime recoveredAt = LocalDateTime.now();
        jdbcTemplate.update("""
            UPDATE embedding_jobs
               SET locked_at = ?, lock_expires_at = ?
             WHERE id = ?
            """, recoveredAt.minusMinutes(2), recoveredAt.minusMinutes(1), context.jobId());
        EmbeddingJobLeaseRecoveryService.RecoveryResult recovery =
            embeddingJobLeaseRecoveryService.recover(context.jobId(), recoveredAt);
        assertThat(recovery.recovered()).isTrue();
        assertThat(recovery.status()).isEqualTo(EmbeddingJobStatus.PENDING);
        assertThat(queryStatus("embedding_job_attempts", context.attemptId())).isEqualTo("FAILED");
        assertThat(queryString(
            "SELECT error_code FROM embedding_job_attempts WHERE id = ?",
            context.attemptId()
        )).isEqualTo("WORKER_LEASE_EXPIRED");
        assertThat(eventCount(context.jobId(), "LEASE_EXPIRED")).isOne();

        // 3. 새 Claim·Attempt가 기존 EMBEDDING Version을 재개해 전체 Vector Set을 한 번만 저장한다.
        jdbcTemplate.update(
            "UPDATE embedding_jobs SET next_retry_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
            context.jobId()
        );
        ClaimedEmbeddingJobResponse secondClaim = embeddingJobClaimService
            .claim(context.workerId())
            .orElseThrow();
        StartedEmbeddingJobAttemptResponse secondAttempt = embeddingJobAttemptService.start(
            context.jobId(),
            new StartEmbeddingJobAttemptRequest(context.workerId(), secondClaim.claimToken())
        ).response();
        EmbeddingWork secondWork = documentEmbeddingTransactionService.prepare(
            context.jobId(),
            secondAttempt.attemptId(),
            context.workerId(),
            secondClaim.claimToken()
        ).work();
        documentEmbeddingTransactionService.complete(
            context.jobId(),
            secondAttempt.attemptId(),
            context.workerId(),
            secondClaim.claimToken(),
            secondWork,
            drafts(secondWork)
        );
        documentIndexingCompletionService.complete(
            context.jobId(),
            secondAttempt.attemptId(),
            new CompleteDocumentIndexingRequest(context.workerId(), secondClaim.claimToken())
        );

        // 4. 최종 원장·파생 상태와 Reconciler 결과가 모두 정상이며 중복 Vector가 없음을 확인한다.
        assertThat(queryStatus("sync_outbox_events", context.eventDatabaseId())).isEqualTo("PROCESSED");
        assertThat(queryStatus("embedding_jobs", context.jobId())).isEqualTo("INDEXED");
        assertThat(queryStatus("embedding_job_attempts", secondAttempt.attemptId())).isEqualTo("SUCCESS");
        assertThat(queryStatus("document_versions", context.versionId())).isEqualTo("INDEXED");
        assertThat(queryStatus("documents", context.documentId())).isEqualTo("INDEXED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT current_version_id FROM documents WHERE id = ?",
            Long.class,
            context.documentId()
        )).isEqualTo(context.versionId());
        assertThat(embeddingCount(context.versionId())).isEqualTo(3);
        assertThat(duplicateVectorGroups(context.versionId(), context.embeddingModelId())).isZero();

        SyncReconciliationBatchResult reconciliation = syncReconciliationOrchestrator.reconcileBatch(
            0L,
            SyncReconciliationMode.DRY_RUN
        );
        assertThat(reconciliation.detectedCount()).isZero();
        assertThat(reconciliation.repairRequestedCount()).isZero();
        assertThat(count("SELECT COUNT(*) FROM sync_consistency_issues")).isZero();
    }

    private ExecutionContext insertExecution() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            SELECT id FROM users WHERE email = 'kcw130502@gmail.com'
            """, Long.class);
        Long workerId = jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, status, last_heartbeat_at, started_at, created_at, updated_at
            ) VALUES ('embedding-recovery-worker', ?, 'ACTIVE', CURRENT_TIMESTAMP,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, suffix);
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at, updated_at
            ) VALUES (?, 'Embedding Recovery Document', 'TXT', 'UPLOAD', 'UPLOADED', 'PRIVATE',
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            ) VALUES (?, 1, 'Embedding Recovery Version', 'text/plain', 'CHUNKED', ?,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
        jdbcTemplate.update("UPDATE documents SET current_version_id = ? WHERE id = ?", versionId, documentId);
        insertChunks(versionId);
        Long embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
        UUID eventId = UUID.randomUUID();
        Long eventDatabaseId = jdbcTemplate.queryForObject("""
            INSERT INTO sync_outbox_events (
                event_id, idempotency_key, aggregate_type, aggregate_id, aggregate_version,
                event_type, payload_json, status, available_at, occurred_at, processed_at,
                retry_count, max_retry_count, created_at, updated_at
            ) VALUES (?, ?, 'DOCUMENT_VERSION', ?, 1, 'DOCUMENT_VERSION_CREATED', ?,
                      'PROCESSED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                      0, 5, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """,
            Long.class,
            eventId,
            "embedding-failure:" + suffix,
            versionId,
            "{\"embeddingModelId\":" + embeddingModelId + "}"
        );
        Long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count, max_retry_count,
                locked_by_worker_id, locked_at, lock_expires_at, claim_token, started_at,
                source_event_id, created_at, updated_at
            ) VALUES (?, ?, 'PROCESSING', 0, 0, 3, ?, CURRENT_TIMESTAMP,
                      TIMESTAMP '2099-01-01 00:00:00', ?, CURRENT_TIMESTAMP, ?,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, embeddingModelId, workerId, FIRST_CLAIM_TOKEN, eventId);
        Long attemptId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_job_attempts (
                embedding_job_id, worker_node_id, attempt_no, claim_token, status,
                started_at, created_at, updated_at
            ) VALUES (?, ?, 1, ?, 'STARTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, jobId, workerId, FIRST_CLAIM_TOKEN);
        return new ExecutionContext(
            workerId,
            jobId,
            attemptId,
            documentId,
            versionId,
            embeddingModelId,
            eventDatabaseId
        );
    }

    private void insertChunks(Long versionId) {
        jdbcTemplate.update("""
            INSERT INTO document_chunks (
                document_version_id, chunk_index, chunk_text, token_count, char_start, char_end,
                content_hash, created_at, updated_at
            ) VALUES
                (?, 0, '첫 번째', 1, 0, 4, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (?, 1, '두 번째', 1, 4, 8, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                (?, 2, '세 번째', 1, 8, 12, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            versionId, CONTENT_HASH,
            versionId, CONTENT_HASH,
            versionId, CONTENT_HASH
        );
    }

    private List<DocumentEmbeddingDraft> drafts(EmbeddingWork work) {
        return work.chunks().stream()
            .map(chunk -> {
                float[] vector = new float[VECTOR_DIMENSION];
                vector[0] = chunk.chunkIndex() + 1;
                vector[1] = chunk.chunkText().length();
                return new DocumentEmbeddingDraft(
                    chunk.chunkId(),
                    chunk.chunkIndex(),
                    chunk.contentHash(),
                    vector,
                    EmbeddingVectorSupport.calculateHash(vector)
                );
            })
            .toList();
    }

    private void installSecondEmbeddingFailure() {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION docgrid_test_raise_second_embedding_failure()
            RETURNS trigger AS $$
            BEGIN
                IF EXISTS (
                    SELECT 1 FROM document_chunks chunk
                    WHERE chunk.id = NEW.chunk_id AND chunk.chunk_index = 1
                ) THEN
                    RAISE EXCEPTION 'forced failure during second embedding insert';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """);
        jdbcTemplate.execute("""
            CREATE TRIGGER docgrid_test_fail_second_embedding
            BEFORE INSERT ON embeddings
            FOR EACH ROW EXECUTE FUNCTION docgrid_test_raise_second_embedding_failure()
            """);
    }

    private void dropEmbeddingFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + EMBEDDING_TRIGGER + " ON embeddings");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + EMBEDDING_FUNCTION + "()");
    }

    private int embeddingCount(Long versionId) {
        return count("SELECT COUNT(*) FROM embeddings WHERE document_version_id = ?", versionId);
    }

    private int duplicateVectorGroups(Long versionId, Long modelId) {
        return count("""
            SELECT COUNT(*)
            FROM (
                SELECT chunk_id
                FROM embeddings
                WHERE document_version_id = ? AND embedding_model_id = ?
                GROUP BY chunk_id
                HAVING COUNT(*) > 1
            ) duplicate
            """, versionId, modelId);
    }

    private int eventCount(Long jobId, String eventType) {
        return count(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = ?",
            jobId,
            eventType
        );
    }

    private String queryStatus(String table, Long id) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM " + table + " WHERE id = ?",
            String.class,
            id
        );
    }

    private String queryString(String sql, Long id) {
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    /**
     * 장애·복구 실행의 Worker, Job, Attempt, 원장 대상과 Event DB 식별자를 묶는다.
     */
    private record ExecutionContext(
        Long workerId,
        Long jobId,
        Long attemptId,
        Long documentId,
        Long versionId,
        Long embeddingModelId,
        Long eventDatabaseId
    ) {
    }
}
