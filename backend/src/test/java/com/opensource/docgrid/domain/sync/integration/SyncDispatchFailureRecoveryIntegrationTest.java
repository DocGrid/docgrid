package com.opensource.docgrid.domain.sync.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.opensource.docgrid.domain.document.dto.request.DocumentUploadRequest;
import com.opensource.docgrid.domain.document.dto.response.DocumentUploadResponse;
import com.opensource.docgrid.domain.document.enums.StorageProvider;
import com.opensource.docgrid.domain.document.enums.VisibilityType;
import com.opensource.docgrid.domain.document.service.DocumentUploadFacade;
import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.StoredFile;
import com.opensource.docgrid.domain.sync.dto.ClaimedSyncEvent;
import com.opensource.docgrid.domain.sync.dto.SyncReconciliationBatchResult;
import com.opensource.docgrid.domain.sync.entity.SyncOutboxEvent;
import com.opensource.docgrid.domain.sync.enums.SyncEventStatus;
import com.opensource.docgrid.domain.sync.enums.SyncReconciliationMode;
import com.opensource.docgrid.domain.sync.repository.SyncOutboxEventRepository;
import com.opensource.docgrid.domain.sync.service.SyncReconciliationOrchestrator;
import com.opensource.docgrid.domain.sync.service.command.SyncEventClaimService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventDispatchService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventFailureService;
import com.opensource.docgrid.domain.sync.service.command.SyncEventLeaseRecoveryService;
import com.opensource.docgrid.domain.user.repository.UserRepository;
import com.opensource.docgrid.global.support.TruncateDeadlockRetry;

/**
 * Sync Event Claim부터 완료까지 주요 중단 지점에서 Retry와 Lease Recovery 수렴을 검증한다.
 *
 * <p>실제 PostgreSQL Trigger로 Job INSERT와 Event 완료 UPDATE를 실패시키며, 모든 복구 뒤 단일 Job,
 * 정리된 소유권, 성공·실패 전달 Attempt 이력과 장애 원인을 직접 확인한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Sync Dispatch 장애 주입·복구 통합 테스트")
class SyncDispatchFailureRecoveryIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_sync_dispatch_failure_test";
    private static final String JOB_TRIGGER = "docgrid_test_fail_sync_job_insert";
    private static final String JOB_FUNCTION = "docgrid_test_raise_sync_job_insert_failure";
    private static final String COMPLETE_TRIGGER = "docgrid_test_fail_sync_event_complete";
    private static final String COMPLETE_FUNCTION = "docgrid_test_raise_sync_event_complete_failure";

    @Autowired private DocumentUploadFacade documentUploadFacade;
    @Autowired private UserRepository userRepository;
    @Autowired private SyncOutboxEventRepository syncOutboxEventRepository;
    @Autowired private SyncEventClaimService syncEventClaimService;
    @Autowired private SyncEventDispatchService syncEventDispatchService;
    @Autowired private SyncEventFailureService syncEventFailureService;
    @Autowired private SyncEventLeaseRecoveryService syncEventLeaseRecoveryService;
    @Autowired private SyncReconciliationOrchestrator syncReconciliationOrchestrator;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private FileStorageService fileStorageService;

    private Long userId;

    @DynamicPropertySource
    static void configureSchema(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
        registry.add("jwt.secret", () -> "docgrid-sync-dispatch-failure-secret-key-2026");
        registry.add("sync.dispatcher.retry-initial-delay", () -> "1ms");
        registry.add("sync.dispatcher.retry-max-delay", () -> "1ms");
    }

    @BeforeEach
    void resetState() {
        reset(fileStorageService);
        dropFailureTriggers();
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
                file_objects
            RESTART IDENTITY CASCADE
            """);
        userId = userRepository.findByEmail("kcw130502@gmail.com").orElseThrow().getId();
        given(fileStorageService.store(any(InputStream.class), anyLong(), anyString(), anyString()))
            .willAnswer(invocation -> new StoredFile(
                StorageProvider.MINIO,
                "test-bucket",
                "documents/sync-failure/" + UUID.randomUUID()
            ));
    }

    @AfterEach
    void dropTriggersAfterTest() {
        dropFailureTriggers();
    }

    @AfterAll
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("Event Claim 직후 프로세스 중단은 Lease 만료 이력 후 새 Claim으로 완료된다")
    void crashAfterClaim_isRecoveredByLeaseAndRedispatch() {
        EventContext context = eventWithoutJob("crash-after-claim");

        // 1. Claim Transaction만 Commit된 직후 프로세스가 중단된 상황을 강제로 만든다.
        assertThatThrownBy(() -> claimAndCrash())
            .isInstanceOf(ForcedFailure.class)
            .hasMessage("forced crash after event claim");
        SyncOutboxEvent claimed = event(context.eventId());
        assertThat(claimed.getStatus()).isEqualTo(SyncEventStatus.PROCESSING);

        // 2. Lease 만료 시각 뒤 Recovery가 소유권을 회수하고 실패 Attempt 원인을 보존한다.
        LocalDateTime recoveredAt = claimed.getLockExpiresAt().plusSeconds(1);
        jdbcTemplate.update(
            "UPDATE sync_outbox_events SET lock_expires_at = ? WHERE event_id = ?",
            recoveredAt.minusSeconds(1),
            context.eventId()
        );
        assertThat(syncEventLeaseRecoveryService.recover(context.eventId(), recoveredAt).recovered()).isTrue();
        assertThat(event(context.eventId()).getStatus()).isEqualTo(SyncEventStatus.PENDING);
        assertAttempt(1, "FAILED", "SYNC_LEASE_EXPIRED", context.eventId());

        // 3. 새 Claim 세대가 Handler를 다시 실행해 단일 Job과 성공 Attempt로 수렴한다.
        dispatchNext();
        assertRecovered(context, 1);
    }

    @Test
    @DisplayName("Handler 시작 직후 실패는 원인을 기록하고 수정된 Event 재시도로 완료된다")
    void failureAtHandlerStart_isRetriedWithoutDuplicateJob() {
        EventContext context = eventWithoutJob("fail-handler-start");
        Long modelId = activeModelId();
        jdbcTemplate.update(
            "UPDATE sync_outbox_events SET payload_json = '{}' WHERE event_id = ?",
            context.eventId()
        );
        ClaimedSyncEvent firstClaim = syncEventClaimService.claim().orElseThrow();

        assertThatThrownBy(() -> syncEventDispatchService.dispatch(firstClaim))
            .isInstanceOf(RuntimeException.class);
        syncEventFailureService.recordFailure(
            firstClaim.eventId(),
            firstClaim.claimToken(),
            "FORCED_HANDLER_START",
            "Handler 시작 직후 강제 실패"
        );
        assertAttempt(1, "FAILED", "FORCED_HANDLER_START", context.eventId());

        jdbcTemplate.update(
            "UPDATE sync_outbox_events SET payload_json = ? WHERE event_id = ?",
            "{\"embeddingModelId\":" + modelId + "}",
            context.eventId()
        );
        dispatchNext();
        assertRecovered(context, 1);
    }

    @Test
    @DisplayName("Job 생성 직후 실패는 Job을 롤백하고 Retry에서 한 건만 다시 만든다")
    void failureAfterJobCreation_rollsBackAndRetries() {
        EventContext context = eventWithoutJob("fail-after-job");
        installJobInsertFailure(context.eventId());
        ClaimedSyncEvent firstClaim = syncEventClaimService.claim().orElseThrow();

        assertThatThrownBy(() -> syncEventDispatchService.dispatch(firstClaim))
            .isInstanceOf(RuntimeException.class);
        assertThat(jobCount(context.eventId())).isZero();
        syncEventFailureService.recordFailure(
            firstClaim.eventId(),
            firstClaim.claimToken(),
            "FORCED_AFTER_JOB_CREATED",
            "Job 생성 직후 강제 실패"
        );
        assertAttempt(1, "FAILED", "FORCED_AFTER_JOB_CREATED", context.eventId());

        dropFailureTriggers();
        dispatchNext();
        assertRecovered(context, 1);
    }

    @Test
    @DisplayName("Event 완료 직전 실패는 Handler Job과 성공 전이를 롤백하고 Retry에서 완료된다")
    void failureBeforeEventCompletion_rollsBackSideEffectAndRetries() {
        EventContext context = eventWithoutJob("fail-before-complete");
        installEventCompletionFailure(context.eventId());
        ClaimedSyncEvent firstClaim = syncEventClaimService.claim().orElseThrow();

        assertThatThrownBy(() -> syncEventDispatchService.dispatch(firstClaim))
            .isInstanceOf(RuntimeException.class);
        assertThat(jobCount(context.eventId())).isZero();
        assertThat(event(context.eventId()).getStatus()).isEqualTo(SyncEventStatus.PROCESSING);
        syncEventFailureService.recordFailure(
            firstClaim.eventId(),
            firstClaim.claimToken(),
            "FORCED_BEFORE_EVENT_COMPLETE",
            "Event 완료 직전 강제 실패"
        );
        assertAttempt(1, "FAILED", "FORCED_BEFORE_EVENT_COMPLETE", context.eventId());

        dropFailureTriggers();
        dispatchNext();
        assertRecovered(context, 1);
    }

    @Test
    @DisplayName("과거 부분 Commit으로 Job이 유실돼도 Reconciler가 Repair Event로 복구하고 Issue를 해결한다")
    void missingJob_isRecoveredAndResolvedByReconciler() {
        EventContext context = eventWithoutJob("reconcile-missing-job");
        jdbcTemplate.update("""
            UPDATE sync_outbox_events
               SET status = 'PROCESSED', processed_at = CURRENT_TIMESTAMP
             WHERE event_id = ?
            """, context.eventId());

        // 1. 처리 중 Version인데 원인 Event만 완료되고 Job이 없는 과거 손상 상태를 검사한다.
        SyncReconciliationBatchResult repair = syncReconciliationOrchestrator.reconcileBatch(
            0L,
            SyncReconciliationMode.REPAIR
        );
        assertThat(repair.detectedCount()).isOne();
        assertThat(repair.repairRequestedCount()).isOne();
        assertThat(count("""
            SELECT COUNT(*) FROM sync_consistency_issues
            WHERE issue_type = 'MISSING_JOB' AND status = 'REPAIRING'
            """)).isOne();

        // 2. Dispatcher가 Reconciler의 Repair Event를 처리해 단일 Job을 복원한다.
        dispatchNext();
        assertThat(count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE document_version_id = ?",
            context.versionId()
        )).isOne();

        // 3. 재검사는 불일치가 사라졌음을 확인하고 기존 Issue를 RESOLVED로 종결한다.
        SyncReconciliationBatchResult verify = syncReconciliationOrchestrator.reconcileBatch(
            0L,
            SyncReconciliationMode.DRY_RUN
        );
        assertThat(verify.detectedCount()).isZero();
        assertThat(count("""
            SELECT COUNT(*) FROM sync_consistency_issues
            WHERE issue_type = 'MISSING_JOB' AND status = 'RESOLVED'
            """)).isOne();
    }

    private void claimAndCrash() {
        syncEventClaimService.claim().orElseThrow();
        throw new ForcedFailure("forced crash after event claim");
    }

    private void dispatchNext() {
        jdbcTemplate.update(
            "UPDATE sync_outbox_events SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second' "
                + "WHERE status = 'PENDING'"
        );
        ClaimedSyncEvent claim = syncEventClaimService.claim().orElseThrow();
        syncEventDispatchService.dispatch(claim);
    }

    private EventContext eventWithoutJob(String label) {
        String marker = label + "-" + UUID.randomUUID();
        DocumentUploadResponse response = documentUploadFacade.upload(
            userId,
            new DocumentUploadRequest(
                new MockMultipartFile("file", marker + ".txt", "text/plain", marker.getBytes()),
                marker,
                "Sync 장애 복구 통합 테스트",
                VisibilityType.PRIVATE
            )
        );
        UUID eventId = jdbcTemplate.queryForObject(
            "SELECT source_event_id FROM embedding_jobs WHERE id = ?",
            UUID.class,
            response.embeddingJobId()
        );
        jdbcTemplate.update("DELETE FROM embedding_jobs WHERE id = ?", response.embeddingJobId());
        return new EventContext(eventId, response.documentVersionId());
    }

    private void assertRecovered(EventContext context, int expectedRetryCount) {
        SyncOutboxEvent recovered = event(context.eventId());
        assertThat(recovered.getStatus()).isEqualTo(SyncEventStatus.PROCESSED);
        assertThat(recovered.getProcessedAt()).isNotNull();
        assertThat(recovered.getRetryCount()).isEqualTo(expectedRetryCount);
        assertThat(recovered.getClaimToken()).isNull();
        assertThat(recovered.getLockedBy()).isNull();
        assertThat(recovered.getLockExpiresAt()).isNull();
        assertThat(jobCount(context.eventId())).isOne();
        assertThat(count(
            "SELECT COUNT(*) FROM embedding_jobs WHERE document_version_id = ?",
            context.versionId()
        )).isOne();
        assertAttempt(2, "SUCCEEDED", null, context.eventId());
    }

    private void assertAttempt(int attemptNo, String status, String errorCode, UUID eventId) {
        List<Map<String, Object>> attempts = jdbcTemplate.queryForList("""
            SELECT status, error_code, completed_at
            FROM sync_event_delivery_attempts
            WHERE event_id = ? AND attempt_no = ?
            """, eventId, attemptNo);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("status")).isEqualTo(status);
        assertThat(attempts.get(0).get("error_code")).isEqualTo(errorCode);
        assertThat(attempts.get(0).get("completed_at")).isNotNull();
    }

    private SyncOutboxEvent event(UUID eventId) {
        return syncOutboxEventRepository.findByEventId(eventId).orElseThrow();
    }

    private int jobCount(UUID eventId) {
        return count("SELECT COUNT(*) FROM embedding_jobs WHERE source_event_id = ?", eventId);
    }

    private int count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Integer.class, arguments);
    }

    private Long activeModelId() {
        return jdbcTemplate.queryForObject(
            "SELECT id FROM embedding_models WHERE is_active = TRUE AND is_searchable = TRUE",
            Long.class
        );
    }

    private void installJobInsertFailure(UUID eventId) {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION docgrid_test_raise_sync_job_insert_failure()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.source_event_id = '%s'::uuid THEN
                    RAISE EXCEPTION 'forced failure after sync job insert';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.formatted(eventId));
        jdbcTemplate.execute("""
            CREATE TRIGGER docgrid_test_fail_sync_job_insert
            AFTER INSERT ON embedding_jobs
            FOR EACH ROW EXECUTE FUNCTION docgrid_test_raise_sync_job_insert_failure()
            """);
    }

    private void installEventCompletionFailure(UUID eventId) {
        jdbcTemplate.execute("""
            CREATE OR REPLACE FUNCTION docgrid_test_raise_sync_event_complete_failure()
            RETURNS trigger AS $$
            BEGIN
                IF NEW.event_id = '%s'::uuid AND NEW.status = 'PROCESSED' THEN
                    RAISE EXCEPTION 'forced failure before sync event completion';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql
            """.formatted(eventId));
        jdbcTemplate.execute("""
            CREATE TRIGGER docgrid_test_fail_sync_event_complete
            BEFORE UPDATE ON sync_outbox_events
            FOR EACH ROW EXECUTE FUNCTION docgrid_test_raise_sync_event_complete_failure()
            """);
    }

    private void dropFailureTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + JOB_TRIGGER + " ON embedding_jobs");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + JOB_FUNCTION + "()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + COMPLETE_TRIGGER + " ON sync_outbox_events");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + COMPLETE_FUNCTION + "()");
    }

    /**
     * 복구 대상 Sync Event와 파생 Job이 가리켜야 할 Version 식별자다.
     */
    private record EventContext(UUID eventId, Long versionId) {
    }

    /**
     * 프로세스가 Claim 직후 종료된 경계를 테스트 흐름에서 명시하는 강제 예외다.
     */
    private static final class ForcedFailure extends RuntimeException {

        private ForcedFailure(String message) {
            super(message);
        }
    }
}
