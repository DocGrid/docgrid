package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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

import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingEventResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobAttemptResponse;
import com.opensource.docgrid.domain.embedding.dto.response.AdminIndexingJobResponse;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobManualRetryEligibility;
import com.opensource.docgrid.domain.embedding.enums.EmbeddingJobStatus;
import com.opensource.docgrid.domain.embedding.service.query.IndexingJobAdminQueryService;
import com.opensource.docgrid.global.common.response.PageResponse;
import com.opensource.docgrid.global.support.TruncateDeadlockRetry;

/**
 * 실제 PostgreSQL에서 관리자 Job 필터·고정 정렬과 Attempt·Event Pagination Query를 검증한다.
 *
 * <p>격리 Schema에 소유권과 내부 오류·Metadata가 포함된 실행 이력을 구성한 뒤 공개 Query Service가
 * 올바른 행 순서와 안전한 DTO만 반환하는지 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("관리자 인덱싱 조회 PostgreSQL 통합 테스트")
class IndexingJobAdminQueryIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_admin_indexing_query_integration_test";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 8, 8, 10, 0);

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private IndexingJobAdminQueryService indexingJobAdminQueryService;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        // Spring Context 기동에만 사용하는 공개 가능한 Test 전용 값이며 운영 인증 값이 아니다.
        registry.add("jwt.secret", () -> "docgrid-admin-query-integration-test-secret-key-2026");
    }

    @BeforeEach
    void resetState() {
        TruncateDeadlockRetry.execute(jdbcTemplate, """
            TRUNCATE TABLE
                indexing_events,
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
    @DisplayName("상태·문서·현재 Worker 필터가 같은 PROCESSING Job 한 건으로 수렴한다")
    void getJobs_filtersByStatusDocumentAndWorker() {
        Long workerId = insertWorker("filter-worker");
        JobContext matched = insertJob("필터 대상", EmbeddingJobStatus.PROCESSING, workerId, BASE_TIME);
        insertJob("다른 상태", EmbeddingJobStatus.PENDING, null, BASE_TIME.plusMinutes(1));
        insertJob("다른 Worker", EmbeddingJobStatus.PROCESSING, insertWorker("other-worker"),
            BASE_TIME.plusMinutes(2));

        PageResponse<AdminIndexingJobResponse> result = indexingJobAdminQueryService.getJobs(
            EmbeddingJobStatus.PROCESSING,
            matched.documentId(),
            workerId,
            0,
            20
        );

        assertThat(result.totalElements()).isOne();
        assertThat(result.content()).singleElement().satisfies(job -> {
            assertThat(job.jobId()).isEqualTo(matched.jobId());
            assertThat(job.documentId()).isEqualTo(matched.documentId());
            assertThat(job.workerId()).isEqualTo(workerId);
            assertThat(job.errorCode()).isEqualTo("TEST-ERROR");
        });
    }

    @Test
    @DisplayName("Job 목록은 생성 시각과 ID 역순으로 고정되고 Page 경계를 보존한다")
    void getJobs_ordersNewestFirstAndPaginates() {
        JobContext oldest = insertJob("가장 오래된 Job", EmbeddingJobStatus.PENDING, null, BASE_TIME);
        JobContext middle = insertJob("중간 Job", EmbeddingJobStatus.PENDING, null, BASE_TIME.plusMinutes(1));
        JobContext newest = insertJob("최신 Job", EmbeddingJobStatus.PENDING, null, BASE_TIME.plusMinutes(2));

        PageResponse<AdminIndexingJobResponse> first = indexingJobAdminQueryService.getJobs(
            null, null, null, 0, 2
        );
        PageResponse<AdminIndexingJobResponse> second = indexingJobAdminQueryService.getJobs(
            null, null, null, 1, 2
        );

        assertThat(first.content()).extracting(AdminIndexingJobResponse::jobId)
            .containsExactly(newest.jobId(), middle.jobId());
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.first()).isTrue();
        assertThat(first.last()).isFalse();
        assertThat(second.content()).extracting(AdminIndexingJobResponse::jobId)
            .containsExactly(oldest.jobId());
        assertThat(second.last()).isTrue();
    }

    @Test
    @DisplayName("같은 문서의 FAILED Job은 최신 Version만 수동 재처리 가능하다고 반환한다")
    void getJobs_marksOnlyLatestFailedVersionAsEligibleForManualRetry() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status)
            VALUES (?, 'test-password-hash', '재처리 가능 여부 테스트', 'ACTIVE')
            RETURNING id
            """, Long.class, "retry-eligibility-" + suffix + "@example.com");
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at
            ) VALUES (?, '버전별 재처리 가능 여부', 'TXT', 'UPLOAD', 'FAILED', 'PRIVATE', ?)
            RETURNING id
            """, Long.class, userId, BASE_TIME);
        Long oldVersionId = insertFailedVersion(documentId, userId, 1, suffix + "-v1");
        Long latestVersionId = insertFailedVersion(documentId, userId, 2, suffix + "-v2");
        // 이전 검색 가능 Version을 가리키는 정상적인 재인덱싱 실패 상황을 재현한다.
        jdbcTemplate.update(
            "UPDATE documents SET current_version_id = ? WHERE id = ?",
            oldVersionId,
            documentId
        );
        Long oldJobId = insertFailedJob(oldVersionId, BASE_TIME);
        Long latestJobId = insertFailedJob(latestVersionId, BASE_TIME.plusMinutes(1));

        PageResponse<AdminIndexingJobResponse> result = indexingJobAdminQueryService.getJobs(
            EmbeddingJobStatus.FAILED,
            documentId,
            null,
            0,
            20
        );

        assertThat(result.content()).extracting(
            AdminIndexingJobResponse::jobId,
            AdminIndexingJobResponse::manualRetryEligibility
        ).containsExactly(
            tuple(
                latestJobId,
                EmbeddingJobManualRetryEligibility.ELIGIBLE
            ),
            tuple(
                oldJobId,
                EmbeddingJobManualRetryEligibility.SUPERSEDED_VERSION
            )
        );
    }

    @Test
    @DisplayName("Attempt는 번호 역순으로 조회하고 과거 소유권·오류 메시지는 응답 계약에 없다")
    void getAttempts_ordersByAttemptNumberAndHidesInternalFields() {
        Long workerId = insertWorker("attempt-worker");
        JobContext context = insertJob("Attempt 이력", EmbeddingJobStatus.FAILED, null, BASE_TIME);
        insertAttempt(context.jobId(), workerId, 1, "FAILED", BASE_TIME, BASE_TIME.plusSeconds(10));
        insertAttempt(context.jobId(), workerId, 2, "SUCCESS", BASE_TIME.plusMinutes(1),
            BASE_TIME.plusMinutes(1).plusSeconds(5));

        PageResponse<AdminIndexingJobAttemptResponse> result =
            indexingJobAdminQueryService.getAttempts(context.jobId(), 0, 1);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content()).singleElement().satisfies(attempt -> {
            assertThat(attempt.attemptNo()).isEqualTo(2);
            assertThat(attempt.workerId()).isEqualTo(workerId);
            assertThat(attempt.errorCode()).isEqualTo("TEST-ATTEMPT-ERROR");
        });
    }

    @Test
    @DisplayName("Event는 발생 시각 역순으로 조회하고 Metadata 대신 공개 메시지만 반환한다")
    void getEvents_ordersByOccurredAtAndHidesMetadata() {
        JobContext context = insertJob("Event 이력", EmbeddingJobStatus.PENDING, null, BASE_TIME);
        insertEvent(context.jobId(), "JOB_CREATED", null, "PENDING", "Job 생성", BASE_TIME);
        insertEvent(context.jobId(), "RETRY", "PROCESSING", "PENDING", "Retry 예약",
            BASE_TIME.plusMinutes(1));

        PageResponse<AdminIndexingEventResponse> result =
            indexingJobAdminQueryService.getEvents(context.jobId(), 0, 20);

        assertThat(result.content()).extracting(AdminIndexingEventResponse::eventType)
            .extracting(Enum::name)
            .containsExactly("RETRY", "JOB_CREATED");
        assertThat(result.content().get(0).message()).isEqualTo("Retry 예약");
    }

    @Test
    @DisplayName("소유권이 없는 종료 Job 상세은 Worker와 Lease를 null로 반환한다")
    void getJob_returnsNullOwnershipForTerminalJob() {
        JobContext context = insertJob("종료 Job", EmbeddingJobStatus.INDEXED, null, BASE_TIME);

        AdminIndexingJobResponse result = indexingJobAdminQueryService.getJob(context.jobId());

        assertThat(result.workerId()).isNull();
        assertThat(result.workerName()).isNull();
        assertThat(result.lockedAt()).isNull();
        assertThat(result.lockExpiresAt()).isNull();
    }

    private JobContext insertJob(
        String title,
        EmbeddingJobStatus status,
        Long workerId,
        LocalDateTime createdAt
    ) {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status)
            VALUES (?, 'test-password-hash', '관리자 조회 테스트', 'ACTIVE')
            RETURNING id
            """, Long.class, "admin-query-" + suffix + "@example.com");
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id, title, document_type, source_type, status, visibility, created_at
            ) VALUES (?, ?, 'TXT', 'UPLOAD', 'INDEXING', 'PRIVATE', ?)
            RETURNING id
            """, Long.class, userId, title, createdAt);
        String versionStatus = status == EmbeddingJobStatus.INDEXED ? "INDEXED" : "UPLOADED";
        Long versionId = jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, file_hash, status, created_by, created_at
            ) VALUES (?, 1, ?, ?, ?, ?, ?)
            RETURNING id
            """, Long.class, documentId, title, "hash-" + suffix, versionStatus, userId, createdAt);
        jdbcTemplate.update(
            "UPDATE documents SET current_version_id = ?, status = ? WHERE id = ?",
            versionId,
            status == EmbeddingJobStatus.INDEXED ? "INDEXED" : "INDEXING",
            documentId
        );
        Long modelId = jdbcTemplate.queryForObject(
            "SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE",
            Long.class
        );
        Long jobId = jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, locked_by_worker_id, locked_at, lock_expires_at, claim_token,
                started_at, completed_at, error_code, error_message, created_at
            ) VALUES (?, ?, ?, 0, 1, 3, ?, ?, ?, ?, ?, ?, 'TEST-ERROR', 'internal-test-message', ?)
            RETURNING id
            """,
            Long.class,
            versionId,
            modelId,
            status.name(),
            workerId,
            workerId == null ? null : createdAt,
            workerId == null ? null : createdAt.plusMinutes(5),
            workerId == null ? null : UUID.randomUUID().toString(),
            status == EmbeddingJobStatus.PENDING ? null : createdAt,
            status == EmbeddingJobStatus.INDEXED ? createdAt.plusMinutes(2) : null,
            createdAt
        );
        return new JobContext(documentId, versionId, jobId);
    }

    private Long insertWorker(String name) {
        String suffix = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name, instance_id, host_name, ip_address, status, last_heartbeat_at, started_at
            ) VALUES (?, ?, 'test-host', '127.0.0.1', 'ACTIVE', ?, ?)
            RETURNING id
            """, Long.class, name, suffix, BASE_TIME, BASE_TIME.minusMinutes(1));
    }

    private Long insertFailedVersion(
        Long documentId,
        Long userId,
        int versionNo,
        String hash
    ) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id, version_no, title_snapshot, file_hash, status, created_by, created_at
            ) VALUES (?, ?, '버전별 재처리 가능 여부', ?, 'FAILED', ?, ?)
            RETURNING id
            """, Long.class, documentId, versionNo, hash, userId, BASE_TIME.plusMinutes(versionNo));
    }

    private Long insertFailedJob(Long versionId, LocalDateTime createdAt) {
        Long modelId = jdbcTemplate.queryForObject(
            "SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE",
            Long.class
        );
        return jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id, embedding_model_id, status, priority, retry_count,
                max_retry_count, started_at, failed_at, error_code, error_message, created_at
            ) VALUES (?, ?, 'FAILED', 0, 3, 3, ?, ?, 'TEST-ERROR', 'internal-test-message', ?)
            RETURNING id
            """, Long.class, versionId, modelId, createdAt, createdAt.plusSeconds(1), createdAt);
    }

    private void insertAttempt(
        Long jobId,
        Long workerId,
        int attemptNo,
        String status,
        LocalDateTime startedAt,
        LocalDateTime endedAt
    ) {
        jdbcTemplate.update("""
            INSERT INTO embedding_job_attempts (
                embedding_job_id, worker_node_id, attempt_no, claim_token, status,
                started_at, ended_at, duration_ms, error_code, error_message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1000, 'TEST-ATTEMPT-ERROR', 'internal-attempt-message')
            """, jobId, workerId, attemptNo, UUID.randomUUID().toString(), status, startedAt, endedAt);
    }

    private void insertEvent(
        Long jobId,
        String eventType,
        String fromStatus,
        String toStatus,
        String message,
        LocalDateTime occurredAt
    ) {
        jdbcTemplate.update("""
            INSERT INTO indexing_events (
                embedding_job_id, event_type, from_status, to_status, message, metadata_json, occurred_at
            ) VALUES (?, ?, ?, ?, ?, '{"internal":"metadata"}', ?)
            """, jobId, eventType, fromStatus, toStatus, message, occurredAt);
    }

    /** 한 검증 시나리오에서 생성한 Document·Version·Job 식별자를 함께 전달한다. */
    private record JobContext(Long documentId, Long versionId, Long jobId) {
    }
}
