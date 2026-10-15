package com.opensource.docgrid.domain.sync.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.entity.DocumentVersion;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.repository.DocumentVersionRepository;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.service.query.EmbeddingModelQueryService;
import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncEventHandlerRegistry;
import com.opensource.docgrid.domain.sync.service.command.SyncEventClaimService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventDispatchService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventWriter;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.support.TruncateDeadlockRetry;

/**
 * at-least-once Event 재전달과 다중 Dispatcher 경쟁이 단일 파생 데이터 Set으로 수렴하는지 검증한다.
 *
 * <p>격리 PostgreSQL Schema에서 실제 Unique 제약, 행 잠금, Transaction을 사용하며 Event·Job·Chunk·Vector
 * 개수와 Claim 소유권을 최종 DB 상태로 판정한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Sync Dispatcher 멱등성·동시성 통합 테스트")
class SyncIdempotencyConcurrencyIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_sync_idempotency_test";
    private static final int EVENT_REPLAY_COUNT = 100;
    private static final int REINDEX_REQUEST_COUNT = 20;
    private static final int DISPATCHER_COUNT = 12;
    private static final int QUEUE_EVENT_COUNT = 30;
    private static final long TIMEOUT_SECONDS = 20;
    private static final int VECTOR_DIMENSION = 1024;
    private static final String VECTOR_TEXT = "[" + String.join(
        ",",
        Collections.nCopies(VECTOR_DIMENSION, "0.01")
    ) + "]";

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private UserRepository userRepository;
    @Autowired private DocumentVersionRepository documentVersionRepository;
    @Autowired private EmbeddingModelQueryService embeddingModelQueryService;
    @Autowired private SyncOutboxEventRepository syncOutboxEventRepository;
    @Autowired private SyncEventWriter syncEventWriter;
    @Autowired private SyncEventHandlerRegistry syncEventHandlerRegistry;
    @Autowired private SyncEventClaimService syncEventClaimService;
    @Autowired private SyncEventDispatchService syncEventDispatchService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private FileStorageService fileStorageService;

    private Long userId;

    @DynamicPropertySource
    static void configureSchema(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-sync-idempotency-integration-secret-key-2026");
    }

    @BeforeEach
    void resetState() {
        reset(fileStorageService);
        TruncateDeadlockRetry.execute(jdbcTemplate, """
            TRUNCATE TABLE
                sync_admin_actions,
                sync_consistency_issues,
                sync_reconciliation_runs,
                embeddings,
                indexing_events,
                document_chunks,
                embedding_job_attempts,
                embedding_jobs,
                sync_outbox_events,
                document_versions,
                documents,
                file_objects
            RESTART IDENTITY CASCADE
            """);
        userId = userRepository.findByEmail("kcw130502@gmail.com").orElseThrow().getId();
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.MINIO,
                "test-bucket",
                "documents/sync-idempotency/" + UUID.randomUUID()
            ));
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("동일 Event를 100회 전달해도 Job·Chunk·Vector는 한 Set만 남는다")
    void replaySameEventHundredTimes_convergesToSingleDerivedSet() {
        DocumentUploadResponse response = uploadDocument("hundred-replays");
        UUID eventId = sourceEventId(response.embeddingJobId());
        Long modelId = activeModelId();
        jdbcTemplate.update("DELETE FROM embedding_jobs WHERE id = ?", response.embeddingJobId());

        // 1. 첫 전달이 누락된 Job을 복원한다.
        handleInTransaction(eventId);
        insertDerivedSet(response, modelId);

        // 2. 완료 응답 유실을 가정해 같은 Event를 99회 더 전달한다.
        for (int replay = 1; replay < EVENT_REPLAY_COUNT; replay++) {
            handleInTransaction(eventId);
        }

        // 3. Event 세대별 Job과 Version별 Chunk·Vector가 정확히 한 Set인지 확인한다.
        assertThat(count("SELECT COUNT(*) FROM embedding_jobs WHERE source_event_id = ?", eventId)).isOne();
        assertThat(count(
            "SELECT COUNT(*) FROM document_chunks WHERE document_version_id = ?",
            response.documentVersionId()
        )).isEqualTo(2);
        assertThat(count(
            "SELECT COUNT(*) FROM embeddings WHERE document_version_id = ? AND embedding_model_id = ?",
            response.documentVersionId(),
            modelId
        )).isEqualTo(2);
        assertThat(duplicateChunkGroups(response.documentVersionId())).isZero();
        assertThat(duplicateVectorGroups(response.documentVersionId(), modelId)).isZero();

        // 4. 우회 Insert도 source Event, Chunk index, Chunk·Model Unique 제약에서 차단된다.
        assertSourceEventUniqueConstraint(response, modelId, eventId);
        assertChunkAndVectorUniqueConstraints(response, modelId);
    }

    @Test
    @DisplayName("동일 재인덱싱 요청 20건은 예외 없이 하나의 idempotency key와 Event ID로 수렴한다")
    void concurrentReindexRequests_returnSingleEvent() throws Exception {
        DocumentUploadResponse response = uploadDocument("concurrent-reindex");
        DocumentVersion version = documentVersionRepository.findById(response.documentVersionId()).orElseThrow();
        EmbeddingModel model = embeddingModelQueryService.getActiveModel();
        String requestKey = "same-request-" + UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(REINDEX_REQUEST_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(REINDEX_REQUEST_COUNT);
        List<Future<SyncOutboxEvent>> futures = new ArrayList<>();

        try {
            for (int request = 0; request < REINDEX_REQUEST_COUNT; request++) {
                futures.add(executor.submit(() -> {
                    barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return syncEventWriter.recordDocumentReindexRequested(version, model, requestKey);
                }));
            }

            Set<UUID> returnedEventIds = ConcurrentHashMap.newKeySet();
            for (Future<SyncOutboxEvent> future : futures) {
                returnedEventIds.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getEventId());
            }
            assertThat(returnedEventIds).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        String idempotencyKey = "DOCUMENT_VERSION:%d:DOCUMENT_REINDEX_REQUESTED:%d:%s".formatted(
            response.documentVersionId(),
            model.getId(),
            requestKey
        );
        assertThat(count(
            "SELECT COUNT(*) FROM sync_outbox_events WHERE idempotency_key = ?",
            idempotencyKey
        )).isOne();
        assertIdempotencyKeyUniqueConstraint(response, model.getId(), idempotencyKey);
    }

    @Test
    @DisplayName("12개 Dispatcher가 30개 Queue를 제한 시간 안에 중복 소유권 없이 모두 처리한다")
    void competingDispatchers_drainQueueWithoutDuplicateOwnership() throws Exception {
        List<UUID> eventIds = new ArrayList<>();
        for (int index = 0; index < QUEUE_EVENT_COUNT; index++) {
            DocumentUploadResponse response = uploadDocument("queue-" + index);
            eventIds.add(sourceEventId(response.embeddingJobId()));
        }
        jdbcTemplate.update(
            "DELETE FROM embedding_jobs WHERE source_event_id = ANY (?)",
            (Object) eventIds.toArray(UUID[]::new)
        );

        CyclicBarrier barrier = new CyclicBarrier(DISPATCHER_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(DISPATCHER_COUNT);
        Set<UUID> claimedEventIds = ConcurrentHashMap.newKeySet();
        Instant startedAt = Instant.now();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int dispatcher = 0; dispatcher < DISPATCHER_COUNT; dispatcher++) {
                futures.add(executor.submit(() -> drainQueue(barrier, claimedEventIds)));
            }

            int processedCount = 0;
            for (Future<Integer> future : futures) {
                processedCount += future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            assertThat(processedCount).isEqualTo(QUEUE_EVENT_COUNT);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(TIMEOUT_SECONDS));
        assertThat(claimedEventIds).containsExactlyInAnyOrderElementsOf(eventIds);
        assertThat(countForEvents(
            "SELECT COUNT(*) FROM sync_outbox_events WHERE event_id = ANY (?) AND status = 'PROCESSED'",
            eventIds
        )).isEqualTo(QUEUE_EVENT_COUNT);
        assertThat(countForEvents("""
            SELECT COUNT(*)
            FROM sync_outbox_events
            WHERE event_id = ANY (?)
              AND (claim_token IS NOT NULL OR locked_by IS NOT NULL OR lock_expires_at IS NOT NULL)
            """, eventIds)).isZero();
        assertThat(countForEvents(
            "SELECT COUNT(*) FROM embedding_jobs WHERE source_event_id = ANY (?)",
            eventIds
        )).isEqualTo(QUEUE_EVENT_COUNT);
        assertThat(countForEvents("""
            SELECT COUNT(*)
            FROM (
                SELECT source_event_id
                FROM embedding_jobs
                WHERE source_event_id = ANY (?)
                GROUP BY source_event_id
                HAVING COUNT(*) > 1
            ) duplicate
            """, eventIds)).isZero();
    }

    private int drainQueue(CyclicBarrier barrier, Set<UUID> claimedEventIds) throws Exception {
        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        int processedCount = 0;
        while (true) {
            ClaimedSyncEvent claim = syncEventClaimService.claim().orElse(null);
            if (claim == null) {
                return processedCount;
            }
            assertThat(claimedEventIds.add(claim.eventId())).isTrue();
            syncEventDispatchService.dispatch(claim);
            processedCount++;
        }
    }

    private void handleInTransaction(UUID eventId) {
        transactionTemplate.executeWithoutResult(status -> {
            SyncOutboxEvent event = syncOutboxEventRepository.findByEventId(eventId).orElseThrow();
            syncEventHandlerRegistry.handle(event);
        });
    }

    private DocumentUploadResponse uploadDocument(String label) {
        String marker = label + "-" + UUID.randomUUID();
        return documentUploadFacade.upload(
            userId,
            new DocumentUploadRequest(
                new MockMultipartFile("file", marker + ".txt", "text/plain", marker.getBytes()),
                marker,
                "Sync 멱등성 통합 테스트",
                VisibilityType.PRIVATE
            )
        );
    }

    private void insertDerivedSet(DocumentUploadResponse response, Long modelId) {
        Long firstChunkId = insertChunk(response.documentVersionId(), 0, "첫 번째", "a".repeat(64));
        Long secondChunkId = insertChunk(response.documentVersionId(), 1, "두 번째", "b".repeat(64));
        insertEmbedding(response, modelId, firstChunkId, "c".repeat(64));
        insertEmbedding(response, modelId, secondChunkId, "d".repeat(64));
        jdbcTemplate.update(
            "UPDATE document_versions SET status = 'INDEXED', indexed_at = CURRENT_TIMESTAMP WHERE id = ?",
            response.documentVersionId()
        );
        jdbcTemplate.update("UPDATE documents SET status = 'INDEXED' WHERE id = ?", response.documentId());
    }

    private Long insertChunk(Long versionId, int chunkIndex, String text, String contentHash) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_chunks (
                document_version_id, chunk_index, chunk_text, token_count,
                char_start, char_end, content_hash, created_at, updated_at
            ) VALUES (?, ?, ?, 1, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, chunkIndex, text, chunkIndex * 10, chunkIndex * 10 + text.length(), contentHash);
    }

    private void insertEmbedding(
        DocumentUploadResponse response,
        Long modelId,
        Long chunkId,
        String vectorHash
    ) {
        jdbcTemplate.update("""
            INSERT INTO embeddings (
                chunk_id, document_id, document_version_id, embedding_model_id,
                vector, dimension, vector_hash, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            chunkId,
            response.documentId(),
            response.documentVersionId(),
            modelId,
            VECTOR_TEXT,
            VECTOR_DIMENSION,
            vectorHash
        );
    }

    private void assertSourceEventUniqueConstraint(
        DocumentUploadResponse response,
        Long modelId,
        UUID eventId
    ) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority,
                retry_count, max_retry_count, source_event_id, created_at, updated_at
            ) VALUES (?, ?, 'PENDING', 0, 0, 3, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, response.documentVersionId(), modelId, eventId))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertChunkAndVectorUniqueConstraints(DocumentUploadResponse response, Long modelId) {
        assertThatThrownBy(() -> insertChunk(
            response.documentVersionId(),
            0,
            "중복 Chunk",
            "e".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class);

        Long chunkId = jdbcTemplate.queryForObject(
            "SELECT id FROM document_chunks WHERE document_version_id = ? AND chunk_index = 0",
            Long.class,
            response.documentVersionId()
        );
        assertThatThrownBy(() -> insertEmbedding(response, modelId, chunkId, "f".repeat(64)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertIdempotencyKeyUniqueConstraint(
        DocumentUploadResponse response,
        Long modelId,
        String idempotencyKey
    ) {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO sync_outbox_events (
                event_id, idempotency_key, aggregate_type, aggregate_id, aggregate_version,
                event_type, payload_json, status, available_at, occurred_at,
                retry_count, max_retry_count, created_at, updated_at
            ) VALUES (?, ?, 'DOCUMENT_VERSION', ?, 1, 'DOCUMENT_REINDEX_REQUESTED', ?,
                      'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 5,
                      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            UUID.randomUUID(),
            idempotencyKey,
            response.documentVersionId(),
            "{\"embeddingModelId\":" + modelId + "}"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID sourceEventId(Long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT source_event_id FROM embedding_jobs WHERE id = ?",
            UUID.class,
            jobId
        );
    }

    private Long activeModelId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE",
            Long.class
        );
    }

    private int duplicateChunkGroups(Long versionId) {
        return count("""
            SELECT COUNT(*)
            FROM (
                SELECT chunk_index
                FROM document_chunks
                WHERE document_version_id = ?
                GROUP BY chunk_index
                HAVING COUNT(*) > 1
            ) duplicate
            """, versionId);
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

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private int countForEvents(String sql, List<UUID> eventIds) {
        return jdbcTemplate.queryForObject(sql, Integer.class, (Object) eventIds.toArray(UUID[]::new));
    }
}
