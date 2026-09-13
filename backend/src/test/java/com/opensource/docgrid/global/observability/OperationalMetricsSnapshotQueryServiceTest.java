package com.opensource.docgrid.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 운영 상태 aggregate SQL의 claim 가능 시각, 상태 수와 가장 오래된 시각을 실제 PostgreSQL에서 검증한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(OperationalMetricsSnapshotQueryService.class)
@DisplayName("운영 상태 Snapshot Query 테스트")
class OperationalMetricsSnapshotQueryServiceTest {

    private static final String TEST_SCHEMA = "docgrid_operational_metrics_snapshot_query_test";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 19, 0);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OperationalMetricsSnapshotQueryService queryService;

    private Long versionId;
    private Long embeddingModelId;
    private Long queryId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-operational-metrics-query-test-secret-key-2026");
        registry.add("indexing.worker.dead-threshold", () -> "30s");
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                embedding_jobs,
                worker_nodes,
                rag_responses,
                search_queries,
                search_conversations,
                sync_outbox_events,
                document_versions,
                documents,
                users
            RESTART IDENTITY CASCADE
            """);

        String suffix = UUID.randomUUID().toString();
        Long userId = insertUser(suffix);
        Long documentId = insertDocument(userId);
        versionId = insertVersion(documentId, userId);
        embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
        Long conversationId = insertConversation(userId);
        queryId = insertQuery(userId, conversationId);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("미래 backoff를 제외하고 Queue별 수·나이와 유효 Worker를 집계한다")
    void load_excludesFutureBackoffAndCountsOperationalState() {
        insertEmbeddingJob("PENDING", NOW.minusMinutes(1), null);
        insertEmbeddingJob("PENDING", NOW.minusDays(2), NOW.minusMinutes(2));
        insertEmbeddingJob("PENDING", NOW.minusDays(3), NOW.plusMinutes(5));
        insertEmbeddingJob("PROCESSING", NOW.minusMinutes(3), null);
        insertWorker("fresh-active", "ACTIVE", NOW.minusSeconds(10));
        insertWorker("expired-idle", "IDLE", NOW.minusSeconds(30));
        insertWorker("fresh-stopped", "STOPPED", NOW.minusSeconds(5));

        insertRagResponse("PROCESSING", NOW.minusMinutes(4));
        insertRagResponse("SUCCESS", NOW.minusHours(1));

        insertSyncEvent("PENDING", NOW.minusMinutes(3));
        insertSyncEvent("PENDING", NOW.plusMinutes(3));
        insertSyncEvent("PROCESSING", NOW.minusMinutes(5));

        OperationalMetricsSnapshot snapshot = queryService.load(NOW);

        assertThat(snapshot.embeddingClaimableJobs()).isEqualTo(2);
        assertThat(snapshot.embeddingProcessingJobs()).isEqualTo(1);
        assertThat(snapshot.embeddingOldestClaimableAt()).isEqualTo(NOW.minusMinutes(2));
        assertThat(snapshot.embeddingActiveWorkers()).isEqualTo(1);
        assertThat(snapshot.ragProcessingJobs()).isEqualTo(1);
        assertThat(snapshot.ragOldestProcessingAt()).isEqualTo(NOW.minusMinutes(4));
        assertThat(snapshot.syncClaimableEvents()).isEqualTo(1);
        assertThat(snapshot.syncProcessingEvents()).isEqualTo(1);
        assertThat(snapshot.syncOldestClaimableAt()).isEqualTo(NOW.minusMinutes(3));
    }

    @Test
    @DisplayName("대상 행이 없으면 개수는 0이고 가장 오래된 시각은 null이다")
    void load_returnsZerosAndNullAgesWhenQueuesAreEmpty() {
        OperationalMetricsSnapshot snapshot = queryService.load(NOW);

        assertThat(snapshot.embeddingClaimableJobs()).isZero();
        assertThat(snapshot.embeddingProcessingJobs()).isZero();
        assertThat(snapshot.embeddingOldestClaimableAt()).isNull();
        assertThat(snapshot.embeddingActiveWorkers()).isZero();
        assertThat(snapshot.ragProcessingJobs()).isZero();
        assertThat(snapshot.ragOldestProcessingAt()).isNull();
        assertThat(snapshot.syncClaimableEvents()).isZero();
        assertThat(snapshot.syncProcessingEvents()).isZero();
        assertThat(snapshot.syncOldestClaimableAt()).isNull();
    }

    private Long insertUser(String suffix) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Operational Metrics User', 'ACTIVE', ?, ?)
            RETURNING id
            """, Long.class, "operational-metrics-" + suffix + "@example.com", NOW, NOW);
    }

    private Long insertDocument(Long userId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at, updated_at
            ) VALUES (?, 'Operational Metrics Document', 'TXT', 'UPLOAD', 'INDEXING', 'PRIVATE', ?, ?)
            RETURNING id
            """, Long.class, userId, NOW, NOW);
    }

    private Long insertVersion(Long documentId, Long userId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status, created_by, created_at, updated_at
            ) VALUES (?, 1, 'Operational Metrics Version', 'text/plain', 'EMBEDDING', ?, ?, ?)
            RETURNING id
            """, Long.class, documentId, userId, NOW, NOW);
    }

    private Long insertConversation(Long userId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO search_conversations (user_id, title, last_message_at, created_at, updated_at)
            VALUES (?, 'Operational Metrics Conversation', ?, ?, ?)
            RETURNING id
            """, Long.class, userId, NOW, NOW, NOW);
    }

    private Long insertQuery(Long userId, Long conversationId) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO search_queries (
                user_id, conversation_id, query_text, search_type, top_k, status, created_at, updated_at
            ) VALUES (?, ?, 'Operational metrics query', 'VECTOR', 5, 'SUCCESS', ?, ?)
            RETURNING id
            """, Long.class, userId, conversationId, NOW, NOW);
    }

    private void insertEmbeddingJob(String status, LocalDateTime createdAt, LocalDateTime nextRetryAt) {
        jdbcTemplate.update("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, next_retry_at, created_at, updated_at
            ) VALUES (?, ?, ?, 0, 0, 3, ?, ?, ?)
            """, versionId, embeddingModelId, status, nextRetryAt, createdAt, createdAt);
    }

    private void insertWorker(String instanceId, String status, LocalDateTime heartbeatAt) {
        jdbcTemplate.update("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, host_name, ip_address, status,
                last_heartbeat_at, started_at, created_at, updated_at
            ) VALUES ('indexing-worker', ?, 'test-host', '127.0.0.1', ?, ?, ?, ?, ?)
            """, instanceId, status, heartbeatAt, NOW.minusHours(1), NOW.minusHours(1), NOW);
    }

    private void insertRagResponse(String status, LocalDateTime createdAt) {
        jdbcTemplate.update("""
            INSERT INTO rag_responses (query_id, answer_text, status, created_at, updated_at)
            VALUES (?, '', ?, ?, ?)
            """, queryId, status, createdAt, createdAt);
    }

    private void insertSyncEvent(String status, LocalDateTime availableAt) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
            INSERT INTO sync_outbox_events (
                event_id, idempotency_key, aggregate_type, aggregate_id, event_type,
                status, available_at, occurred_at, retry_count, max_retry_count, created_at, updated_at
            ) VALUES (?::uuid, ?, 'DOCUMENT', 1, 'DOCUMENT_CREATED', ?, ?, ?, 0, 3, ?, ?)
            """, eventId, "operational-metrics-" + eventId, status, availableAt,
            availableAt.minusMinutes(1), availableAt.minusMinutes(1), availableAt.minusMinutes(1));
    }
}
