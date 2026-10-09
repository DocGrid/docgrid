package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.embedding.dto.response.ClaimedEmbeddingJobResponse;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobClaimService;

/**
 * 실제 OpenSQL에서 Embedding Job Queue의 정렬, 행 잠금, 동시 Claim 불변식을 검증하는 통합 테스트.
 *
 * <p>서로 다른 Thread와 {@code REQUIRES_NEW} Transaction을 사용해 단일 Persistence Context의 순차
 * 호출로는 재현할 수 없는 {@code FOR UPDATE SKIP LOCKED} 경쟁을 검증한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding Job Claim DB 동시성 통합 테스트")
class EmbeddingJobClaimIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_embedding_job_claim_test";
    private static final long TIMEOUT_SECONDS = 10;
    private static final LocalDateTime CLAIMED_AT = LocalDateTime.of(2026, 8, 3, 10, 0);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EmbeddingJobRepository embeddingJobRepository;

    @Autowired
    private EmbeddingJobClaimService embeddingJobClaimService;

    private ExecutorService executorService;
    private final AtomicInteger threadSequence = new AtomicInteger();

    @DynamicPropertySource
    static void useIsolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("TEST_DB_SCHEMA", () -> TEST_SCHEMA);
    }

    @BeforeAll
    void createExecutor() {
        executorService = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("job-claim-worker-" + threadSequence.incrementAndGet());
            return thread;
        });
    }

    @BeforeEach
    void resetClaimState() {
        jdbcTemplate.update("DELETE FROM indexing_events");
        jdbcTemplate.update("DELETE FROM embedding_job_attempts");
        jdbcTemplate.update("DELETE FROM embedding_jobs");
        jdbcTemplate.update("DELETE FROM worker_nodes");
    }

    @AfterAll
    void cleanUpSchemaAndExecutor() throws InterruptedException {
        executorService.shutdownNow();
        assertThat(executorService.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("PENDING Job을 priority 내림차순, 생성 시각 오름차순으로 선택한다")
    void findNextPendingForUpdate_ordersClaimCandidate() {
        Long documentVersionId = insertDocumentVersion();
        Long lowPriorityJobId = insertJob(documentVersionId, "PENDING", 1, "2026-07-22 10:00:00");
        Long oldHighPriorityJobId = insertJob(documentVersionId, "PENDING", 10, "2026-07-22 09:00:00");
        insertJob(documentVersionId, "PENDING", 10, "2026-07-22 11:00:00");
        insertJob(documentVersionId, "PROCESSING", 100, "2026-07-22 08:00:00");

        Long selectedJobId = inNewTransaction(() ->
            embeddingJobRepository.findNextPendingForUpdate(CLAIMED_AT).orElseThrow().getId()
        );

        assertThat(selectedJobId).isEqualTo(oldHighPriorityJobId);
        assertThat(selectedJobId).isNotEqualTo(lowPriorityJobId);
    }

    @Test
    @DisplayName("시험 문서 버전의 Claim 조회는 더 높은 우선순위의 다른 문서 Job을 제외한다")
    void findNextPendingForDocumentVersionForUpdate_excludesOtherVersions() {
        Long selectedVersionId = insertDocumentVersion();
        Long otherVersionId = insertDocumentVersion();
        Long selectedJobId = insertJob(selectedVersionId, "PENDING", 1, "2026-07-22 10:00:00");
        insertJob(otherVersionId, "PENDING", 100, "2026-07-22 09:00:00");

        Long actualJobId = inNewTransaction(() -> embeddingJobRepository
            .findNextPendingForDocumentVersionForUpdate(CLAIMED_AT, selectedVersionId)
            .orElseThrow()
            .getId());

        assertThat(actualJobId).isEqualTo(selectedJobId);
    }

    @Test
    @DisplayName("시험 문서 버전의 Lease 복구 후보 조회는 다른 문서를 제외한다")
    void findExpiredProcessingJobIdsForDocumentVersion_excludesOtherVersions() {
        Long selectedVersionId = insertDocumentVersion();
        Long otherVersionId = insertDocumentVersion();
        Long selectedJobId = insertJob(selectedVersionId, "PROCESSING", 1, "2026-07-22 10:00:00");
        Long otherJobId = insertJob(otherVersionId, "PROCESSING", 1, "2026-07-22 09:00:00");
        jdbcTemplate.update(
            "UPDATE embedding_jobs SET lock_expires_at = CAST(? AS TIMESTAMP) WHERE id IN (?, ?)",
            "2026-08-03 09:00:00",
            selectedJobId,
            otherJobId
        );

        assertThat(embeddingJobRepository.findExpiredProcessingJobIdsForDocumentVersion(
            CLAIMED_AT,
            100,
            selectedVersionId
        )).containsExactly(selectedJobId);
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 PENDING Job은 기다리지 않고 다음 Job을 선택한다")
    void findNextPendingForUpdate_skipsLockedRow() throws Exception {
        Long documentVersionId = insertDocumentVersion();
        Long firstJobId = insertJob(documentVersionId, "PENDING", 10, "2026-07-22 09:00:00");
        Long secondJobId = insertJob(documentVersionId, "PENDING", 1, "2026-07-22 10:00:00");
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        // 1. 첫 번째 Transaction이 최우선 Job의 행 잠금을 잡은 채 Commit을 지연한다.
        Future<Long> lockHolder = executorService.submit(() -> inNewTransaction(() -> {
            Long selectedId = embeddingJobRepository.findNextPendingForUpdate(CLAIMED_AT).orElseThrow().getId();
            rowLocked.countDown();
            awaitLatch(releaseLock);
            return selectedId;
        }));

        // 2. 첫 번째 행이 실제로 잠긴 뒤에만 두 번째 Transaction을 시작해 경쟁 조건을 확정한다.
        assertThat(rowLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 3. 두 번째 Transaction은 잠금 해제를 기다리지 않고 다음 PENDING Job을 선택해야 한다.
        Future<Long> skipLockedReader = executorService.submit(() -> inNewTransaction(() ->
            embeddingJobRepository.findNextPendingForUpdate(CLAIMED_AT).orElseThrow().getId()
        ));

        try {
            assertThat(skipLockedReader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(secondJobId);
        } finally {
            releaseLock.countDown();
        }

        // 4. 잠금을 보유했던 첫 번째 Transaction은 원래의 최우선 Job을 선택했는지 확인한다.
        assertThat(lockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(firstJobId);
    }

    @Test
    @DisplayName("Retry 예약 시각 이전에는 제외하고 정확히 예약 시각부터 Claim 후보가 된다")
    void findNextPendingForUpdate_respectsNextRetryAtBoundary() {
        Long documentVersionId = insertDocumentVersion();
        Long immediateJobId = insertJob(documentVersionId, "PENDING", 1, "2026-08-03 09:00:00");
        Long retryJobId = insertJob(documentVersionId, "PENDING", 100, "2026-08-03 08:00:00");
        jdbcTemplate.update(
            "UPDATE embedding_jobs SET next_retry_at = CAST(? AS TIMESTAMP) WHERE id = ?",
            "2026-08-03 10:00:00",
            retryJobId
        );

        Long beforeRetry = inNewTransaction(() -> embeddingJobRepository
            .findNextPendingForUpdate(CLAIMED_AT.minusNanos(1_000))
            .orElseThrow()
            .getId());
        Long atRetry = inNewTransaction(() -> embeddingJobRepository
            .findNextPendingForUpdate(CLAIMED_AT)
            .orElseThrow()
            .getId());

        assertThat(beforeRetry).isEqualTo(immediateJobId);
        assertThat(atRetry).isEqualTo(retryJobId);
    }

    @Test
    @DisplayName("두 Worker가 동시에 하나의 Job을 Claim해도 한 Worker만 소유권을 얻는다")
    void claim_allowsExactlyOneConcurrentOwner() throws Exception {
        Long documentVersionId = insertDocumentVersion();
        Long jobId = insertJob(documentVersionId, "PENDING", 10, "2026-07-22 09:00:00");
        Long firstWorkerId = insertActiveWorker("worker-a");
        Long secondWorkerId = insertActiveWorker("worker-b");
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        // 1. 두 Worker가 Barrier를 통과한 직후 같은 PENDING Job을 동시에 Claim하도록 시도한다.
        List<Future<Optional<ClaimedEmbeddingJobResponse>>> attempts = List.of(
            executorService.submit(() -> claimAfterBarrier(firstWorkerId, startBarrier)),
            executorService.submit(() -> claimAfterBarrier(secondWorkerId, startBarrier))
        );

        // 2. 두 독립 Transaction의 결과를 모두 수집해 성공과 빈 결과의 개수를 비교한다.
        List<Optional<ClaimedEmbeddingJobResponse>> results = List.of(
            attempts.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            attempts.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        );

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(results).filteredOn(Optional::isEmpty).hasSize(1);

        // 3. 성공한 한 Worker만 유효한 UUID Token과 미래의 Lease 만료 시각을 받았는지 확인한다.
        ClaimedEmbeddingJobResponse claimedJob = results.stream()
            .flatMap(Optional::stream)
            .findFirst()
            .orElseThrow();
        assertThat(claimedJob.jobId()).isEqualTo(jobId);
        assertThat(claimedJob.workerId()).isIn(firstWorkerId, secondWorkerId);
        assertThatCodeIsUuid(claimedJob.claimToken());
        assertThat(claimedJob.lockExpiresAt()).isAfter(claimedJob.lockedAt());

        // 4. API 결과뿐 아니라 DB 상태와 이벤트도 정확히 한 소유자 기준으로 Commit됐는지 확인한다.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM embedding_jobs WHERE id = ?",
            String.class,
            jobId
        )).isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked_by_worker_id FROM embedding_jobs WHERE id = ?",
            Long.class,
            jobId
        )).isEqualTo(claimedJob.workerId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT claim_token FROM embedding_jobs WHERE id = ?",
            String.class,
            jobId
        )).isEqualTo(claimedJob.claimToken());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM indexing_events WHERE embedding_job_id = ? AND event_type = 'LOCKED'",
            Integer.class,
            jobId
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("V33 Migration이 claim_token 컬럼을 VARCHAR(36)으로 생성한다")
    void migration_createsClaimTokenColumn() {
        Integer length = jdbcTemplate.queryForObject("""
            SELECT character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'embedding_jobs'
              AND column_name = 'claim_token'
            """, Integer.class);

        assertThat(length).isEqualTo(36);
    }

    @Test
    @DisplayName("V35 Migration이 next_retry_at 컬럼과 조회 인덱스를 생성한다")
    void migration_createsNextRetryAtColumnAndIndex() {
        String dataType = jdbcTemplate.queryForObject("""
            SELECT data_type
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'embedding_jobs'
              AND column_name = 'next_retry_at'
            """, String.class);
        Integer indexCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM pg_indexes
            WHERE schemaname = current_schema()
              AND tablename = 'embedding_jobs'
              AND indexname = 'idx_embedding_jobs_status_next_retry_at'
            """, Integer.class);

        assertThat(dataType).isEqualTo("timestamp without time zone");
        assertThat(indexCount).isEqualTo(1);
    }

    private Optional<ClaimedEmbeddingJobResponse> claimAfterBarrier(Long workerId, CyclicBarrier barrier) {
        awaitBarrier(barrier);
        return embeddingJobClaimService.claim(workerId);
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(value).isNotBlank();
        assertThat(UUID.fromString(value).toString()).isEqualTo(value);
    }

    private Long insertDocumentVersion() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Claim Test User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "claim-" + suffix + "@example.com");
        Long documentId = jdbcTemplate.queryForObject("""
            INSERT INTO documents (
                owner_user_id,
                title,
                document_type,
                source_type,
                status,
                visibility,
                created_at,
                updated_at
            )
            VALUES (?, 'Claim Test Document', 'TXT', 'UPLOAD', 'UPLOADED', 'PRIVATE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, userId);
        return jdbcTemplate.queryForObject("""
            INSERT INTO document_versions (
                document_id,
                version_no,
                title_snapshot,
                status,
                created_by,
                created_at,
                updated_at
            )
            VALUES (?, 1, 'Claim Test Version', 'UPLOADED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
    }

    private Long insertJob(Long documentVersionId, String status, int priority, String createdAt) {
        Long embeddingModelId = jdbcTemplate.queryForObject("""
            SELECT id
            FROM embedding_models
            WHERE is_active = TRUE
              AND is_searchable = TRUE
            """, Long.class);
        return jdbcTemplate.queryForObject("""
            INSERT INTO embedding_jobs (
                document_version_id,
                embedding_model_id,
                status,
                priority,
                retry_count,
                max_retry_count,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, 0, 3, CAST(? AS TIMESTAMP), CAST(? AS TIMESTAMP))
            RETURNING id
            """, Long.class, documentVersionId, embeddingModelId, status, priority, createdAt, createdAt);
    }

    private Long insertActiveWorker(String workerName) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO worker_nodes (
                worker_name,
                instance_id,
                host_name,
                ip_address,
                status,
                last_heartbeat_at,
                started_at,
                created_at,
                updated_at
            )
            VALUES (?, ?, 'localhost', '127.0.0.1', 'ACTIVE', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, workerName, UUID.randomUUID().toString());
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> work.get());
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 Lock 해제가 제한 시간 안에 완료되지 않았습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 Lock 대기 중 Thread가 중단되었습니다.", exception);
        }
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트 Barrier 대기 중 Thread가 중단되었습니다.", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("동시성 테스트 Barrier가 제한 시간 안에 완료되지 않았습니다.", exception);
        }
    }
}
