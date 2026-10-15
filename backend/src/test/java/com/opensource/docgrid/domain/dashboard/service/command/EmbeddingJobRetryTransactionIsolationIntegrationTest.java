package com.opensource.docgrid.domain.dashboard.service.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

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

import com.opensource.docgrid.domain.dashboard.dto.response.RetryAllJobsResponse;
import com.opensource.docgrid.global.support.TruncateDeadlockRetry;

/**
 * 전체 재처리 중 한 Job이 실패해도 다른 Job의 성공한 재처리는 실제로 커밋되는지 실제 PostgreSQL로
 * 검증한다.
 *
 * <p>{@link EmbeddingJobRetryService}가 클래스 레벨 {@code @Transactional}을 의도적으로 두지
 * 않는 이유(트랜잭션 독립 커밋)는 Mockito 단위 테스트로는 증명할 수 없다 — Mock은 실제 스프링
 * 트랜잭션 프록시를 거치지 않기 때문이다. 이 테스트는 {@code EmbeddingJobManualRetryService}를
 * Mock 없이 그대로 사용해서 실제 Transaction 경계를 검증한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("EmbeddingJobRetryService 트랜잭션 독립성 통합 테스트")
class EmbeddingJobRetryTransactionIsolationIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_embedding_job_retry_isolation_test";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmbeddingJobRetryService embeddingJobRetryService;

    private Long embeddingModelId;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-embedding-job-retry-isolation-test-secret-key-2026");
    }

    @BeforeEach
    void setUp() {
        TruncateDeadlockRetry.execute(jdbcTemplate, """
            TRUNCATE TABLE
                embedding_jobs,
                document_versions,
                documents,
                users
            RESTART IDENTITY CASCADE
            """);

        embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE AND is_searchable = TRUE
            """, Long.class);
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("전체 재처리 중 한 Job이 재처리 대상 검증에 실패해도 나머지 성공한 Job은 실제로 커밋된다")
    void retryAllFailedJobs_commitsSucceedingJobsIndependently_whenOneJobFails() {
        // Given — 정상 재처리 대상 2건과, 문서가 삭제돼 재처리 대상이 될 수 없는 1건을 섞어 둔다.
        String suffix = UUID.randomUUID().toString();
        Long userId = insertUser(suffix);

        Long succeedingJobId1 = insertRetryableFailedJob(userId, "Retryable Document 1", false);
        Long failingJobId = insertRetryableFailedJob(userId, "Deleted Document", true);
        Long succeedingJobId2 = insertRetryableFailedJob(userId, "Retryable Document 2", false);

        // When
        RetryAllJobsResponse result = embeddingJobRetryService.retryAllFailedJobs();

        // Then — 실패한 1건과 무관하게 성공한 2건은 실제 DB에 PENDING으로 커밋돼 있어야 한다.
        assertThat(result.scannedCount()).isEqualTo(3);
        assertThat(result.retriedCount()).isEqualTo(2);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(statusOf(succeedingJobId1)).isEqualTo("PENDING");
        assertThat(statusOf(succeedingJobId2)).isEqualTo("PENDING");
        assertThat(statusOf(failingJobId)).isEqualTo("FAILED");
    }

    private String statusOf(Long jobId) {
        return jdbcTemplate.queryForObject(
            "SELECT status FROM embedding_jobs WHERE id = ?", String.class, jobId
        );
    }

    private Long insertUser(String suffix) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Retry Isolation Test User', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "embedding-job-retry-isolation-" + suffix + "@example.com");
    }

    /**
     * FAILED 상태 Job 하나와, 그 재처리 대상이 되는 FAILED 문서·버전을 만든다.
     *
     * @param deleted true면 문서를 soft-delete 상태로 만들어,
     *                {@code EmbeddingJobManualRetryService.validateRetryTarget()}이
     *                {@code EMBEDDING_JOB_MANUAL_RETRY_TARGET_INVALID}를 던지도록 유도한다.
     */
    private Long insertRetryableFailedJob(Long userId, String title, boolean deleted) {
        Timestamp deletedAt = deleted ? Timestamp.valueOf(LocalDateTime.now()) : null;

        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, deleted_at,
                created_at, updated_at
            )
            VALUES (?, ?, 'TXT', 'UPLOAD', 'FAILED', 'PRIVATE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId, title, deletedAt);

        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, content_type, status,
                created_by, created_at, updated_at
            )
            VALUES (?, 1, ?, 'text/plain', 'FAILED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, title, userId);

        jdbcTemplate.update(
            "UPDATE documents SET current_version_id = ? WHERE id = ?", versionId, documentId
        );

        return jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, created_at, updated_at
            )
            VALUES (?, ?, 'FAILED', 0, 3, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, versionId, embeddingModelId);
    }
}
