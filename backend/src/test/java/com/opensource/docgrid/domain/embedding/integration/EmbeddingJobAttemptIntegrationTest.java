package com.opensource.docgrid.domain.embedding.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.embedding.dto.request.StartEmbeddingJobAttemptRequest;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingJobRepository;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService;
import com.opensource.docgrid.domain.embedding.service.command.EmbeddingJobAttemptService.StartResult;
import com.opensource.docgrid.domain.worker.entity.EmbeddingJobAttempt;
import com.opensource.docgrid.domain.worker.repository.EmbeddingJobAttemptRepository;

/**
 * 실제 OpenSQL에서 Embedding Job Attempt의 Migration, Repository, 잠금과 멱등 동시성을 검증한다.
 *
 * <p>격리 Schema와 독립 Thread·Transaction을 사용해 V34 제약 및 Job 행 잠금이 같은 Claim의 중복
 * Insert와 Job별 Attempt 번호 충돌을 실제 DB 경계에서 차단하는지 확인한다. 고정된 고정밀 Clock으로
 * 최초 응답과 DB 왕복 후 재전송 응답의 시각 동일성을 OS 시계 정밀도와 무관하게 검증한다.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest
@Import(EmbeddingJobAttemptIntegrationTest.FixedClockConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Embedding Job Attempt OpenSQL 통합 테스트")
class EmbeddingJobAttemptIntegrationTest {

    private static final String TEST_SCHEMA = "docgrid_attempt_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final long TIMEOUT_SECONDS = 10;
    private static final String CLAIM_TOKEN = "34c19d16-6ae1-4f6a-a35d-0123456789ab";
    private static final String NEXT_CLAIM_TOKEN = "8d242ac5-0916-4e1c-a781-1f7b932f989b";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-16T00:00:00.123456789Z");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EmbeddingJobRepository embeddingJobRepository;
    @Autowired private EmbeddingJobAttemptRepository embeddingJobAttemptRepository;
    @Autowired private EmbeddingJobAttemptService embeddingJobAttemptService;
    @Autowired private Clock clock;

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
            thread.setName("job-attempt-worker-" + threadSequence.incrementAndGet());
            return thread;
        });
    }

    @BeforeEach
    void resetAttemptState() {
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
    @DisplayName("V34가 nullable VARCHAR(36) Claim Token과 이름 있는 유일성 제약을 만든다")
    void migration_createsClaimTokenColumnAndConstraint() {
        Integer length = jdbcTemplate.queryForObject("""
            SELECT character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'embedding_job_attempts'
              AND column_name = 'claim_token'
            """, Integer.class);
        String nullable = jdbcTemplate.queryForObject("""
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'embedding_job_attempts'
              AND column_name = 'claim_token'
            """, String.class);
        List<String> constraints = jdbcTemplate.queryForList("""
            SELECT constraint_name
            FROM information_schema.table_constraints
            WHERE table_schema = current_schema()
              AND table_name = 'embedding_job_attempts'
              AND constraint_type = 'UNIQUE'
            ORDER BY constraint_name
            """, String.class);

        assertThat(length).isEqualTo(36);
        assertThat(nullable).isEqualTo("YES");
        assertThat(constraints).contains(
            "uk_embedding_job_attempts_embedding_job_id_attempt_no",
            "uk_embedding_job_attempts_embedding_job_id_claim_token"
        );
    }

    @Test
    @DisplayName("Legacy null Token Attempt 여러 건은 유지되고 두 유일성 제약은 중복 쓰기를 차단한다")
    void constraints_allowLegacyNullTokensAndRejectDuplicates() {
        Long workerId = insertWorker("constraint-worker");
        Long jobId = insertProcessingJob(workerId, CLAIM_TOKEN);
        insertAttempt(jobId, workerId, 1, null);
        insertAttempt(jobId, workerId, 2, null);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ? AND claim_token IS NULL",
            Integer.class,
            jobId
        )).isEqualTo(2);

        insertAttempt(jobId, workerId, 3, CLAIM_TOKEN);
        assertThatThrownBy(() -> insertAttempt(jobId, workerId, 4, CLAIM_TOKEN))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttempt(jobId, workerId, 3, NEXT_CLAIM_TOKEN))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Attempt Repository가 Job·Token과 Job별 가장 큰 번호를 조회한다")
    void repository_findsClaimAttemptAndLatestNumber() {
        Long workerId = insertWorker("repository-worker");
        Long jobId = insertProcessingJob(workerId, CLAIM_TOKEN);
        insertAttempt(jobId, workerId, 1, "11111111-1111-4111-8111-111111111111");
        Long expectedAttemptId = insertAttempt(jobId, workerId, 3, CLAIM_TOKEN);
        insertAttempt(jobId, workerId, 2, "22222222-2222-4222-8222-222222222222");

        EmbeddingJobAttempt byToken = embeddingJobAttemptRepository
            .findByEmbeddingJobIdAndClaimToken(jobId, CLAIM_TOKEN)
            .orElseThrow();
        EmbeddingJobAttempt latest = embeddingJobAttemptRepository
            .findTopByEmbeddingJobIdOrderByAttemptNoDesc(jobId)
            .orElseThrow();

        assertThat(byToken.getId()).isEqualTo(expectedAttemptId);
        assertThat(latest.getAttemptNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("ID 기반 쓰기 잠금은 같은 Job을 조회하는 다음 Transaction을 Commit까지 대기시킨다")
    void findByIdForUpdate_blocksCompetingTransaction() throws Exception {
        Long workerId = insertWorker("lock-worker");
        Long jobId = insertProcessingJob(workerId, CLAIM_TOKEN);
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch competitorStarted = new CountDownLatch(1);

        // 1. 첫 Transaction이 Job 쓰기 잠금을 획득한 뒤 명시적 해제 신호까지 Commit을 지연한다.
        Future<Long> lockHolder = executorService.submit(() -> inNewTransaction(() -> {
            Long selectedId = embeddingJobRepository.findByIdForUpdate(jobId).orElseThrow().getId();
            rowLocked.countDown();
            awaitLatch(releaseLock);
            return selectedId;
        }));
        assertThat(rowLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 2. 두 번째 Transaction이 동일 Job 잠금 조회에 진입했음을 확인한다.
        Future<Long> competitor = executorService.submit(() -> inNewTransaction(() -> {
            competitorStarted.countDown();
            return embeddingJobRepository.findByIdForUpdate(jobId).orElseThrow().getId();
        }));
        assertThat(competitorStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 3. 잠금 보유 중에는 제한 시간 안에 두 번째 조회가 끝나지 않아야 한다.
        try {
            assertThatThrownBy(() -> competitor.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        } finally {
            releaseLock.countDown();
        }

        // 4. 첫 Transaction Commit 뒤에는 두 번째 Transaction도 같은 Job을 조회해 완료한다.
        assertThat(lockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(jobId);
        assertThat(competitor.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(jobId);
    }

    @Test
    @DisplayName("현재 Claim의 순차 재전송은 기존 Attempt를 반환하고 새 Claim은 다음 번호를 만든다")
    void start_isIdempotentAndAssignsNextNumberAcrossClaims() {
        Long workerId = insertWorker("sequential-worker");
        Long jobId = insertProcessingJob(workerId, CLAIM_TOKEN);
        StartEmbeddingJobAttemptRequest firstRequest =
            new StartEmbeddingJobAttemptRequest(workerId, CLAIM_TOKEN);

        StartResult created = embeddingJobAttemptService.start(jobId, firstRequest);
        StartResult replayed = embeddingJobAttemptService.start(jobId, firstRequest);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.response()).isEqualTo(created.response());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ?",
            Integer.class,
            jobId
        )).isEqualTo(1);

        // 새 Claim 세대를 모사해 현재 Token만 교체하면 같은 Job에서 다음 번호가 할당돼야 한다.
        jdbcTemplate.update("""
            UPDATE embedding_jobs
            SET claim_token = ?,
                locked_at = CURRENT_TIMESTAMP,
                lock_expires_at = TIMESTAMP '2099-01-01 00:00:00'
            WHERE id = ?
            """, NEXT_CLAIM_TOKEN, jobId);
        StartResult next = embeddingJobAttemptService.start(
            jobId,
            new StartEmbeddingJobAttemptRequest(workerId, NEXT_CLAIM_TOKEN)
        );

        assertThat(next.created()).isTrue();
        assertThat(next.response().attemptNo()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ?",
            Integer.class,
            jobId
        )).isEqualTo(2);
    }

    @Test
    @DisplayName("고정된 고정밀 시각에서도 DB Commit 후 재전송 응답 전체가 최초 응답과 일치한다")
    void start_replaysSameBodyAfterDatabaseRoundTrip_withSubMicrosecondClock() {
        // 1. 실제 주입된 Clock이 마이크로초보다 정밀한 값인지 확인하고 같은 시각으로 Lease를 준비한다.
        assertThat(clock.instant()).isEqualTo(FIXED_INSTANT);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        LocalDateTime now = LocalDateTime.now(clock);
        assertThat(now.getNano() % 1_000).isNotZero();
        Long workerId = insertWorker("precision-worker");
        Long jobId = insertProcessingJob(workerId, CLAIM_TOKEN);
        jdbcTemplate.update("""
            UPDATE embedding_jobs
            SET locked_at = ?, lock_expires_at = ?, started_at = ?
            WHERE id = ?
            """, now.minusSeconds(1), now.plusMinutes(5), now.minusSeconds(1), jobId);
        StartEmbeddingJobAttemptRequest request = new StartEmbeddingJobAttemptRequest(workerId, CLAIM_TOKEN);
        List<Long> transactionIds = new ArrayList<>();

        // 2. 첫 Transaction을 실제 Commit해 생성 응답과 PostgreSQL에 저장된 값을 각각 확보한다.
        StartResult created = inNewTransaction(() -> {
            transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
            return embeddingJobAttemptService.start(jobId, request);
        });
        LocalDateTime persistedAt = jdbcTemplate.queryForObject("""
            SELECT started_at FROM embedding_job_attempts
            WHERE embedding_job_id = ? AND claim_token = ?
            """, LocalDateTime.class, jobId, CLAIM_TOKEN);

        // 3. 독립 Transaction에서 같은 Claim을 재조회해 1차 캐시가 DB 왕복을 대신하지 않게 한다.
        StartResult replayed = inNewTransaction(() -> {
            transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
            return embeddingJobAttemptService.start(jobId, request);
        });
        Integer attemptCount = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM embedding_job_attempts
            WHERE embedding_job_id = ? AND claim_token = ?
            """, Integer.class, jobId, CLAIM_TOKEN);
        System.out.printf(
            "ATTEMPT_PRECISION_EVIDENCE clock=%s created=%s persisted=%s replayed=%s attempts=%d independentTransactions=%s%n",
            clock.instant(), created.response().startedAt(), persistedAt, replayed.response().startedAt(),
            attemptCount, !transactionIds.get(0).equals(transactionIds.get(1))
        );

        // 4. HTTP 상태 선택용 생성 여부는 달라도 응답 본문 전체와 저장 시각은 같고 Attempt는 한 건이어야 한다.
        assertThat(transactionIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(attemptCount).isEqualTo(1);
        assertThat(replayed.response()).isEqualTo(created.response());
        assertThat(created.response().startedAt()).isEqualTo(persistedAt);
    }

    @Test
    @DisplayName("같은 Job·Worker·Token 동시 요청은 한 Attempt와 생성·재생 결과 하나씩으로 수렴한다")
    void start_concurrentSameClaimCreatesExactlyOneAttempt() throws Exception {
        Long workerId = insertWorker("concurrency-worker");
        Long jobId = insertProcessingJob(workerId, CLAIM_TOKEN);
        StartEmbeddingJobAttemptRequest request =
            new StartEmbeddingJobAttemptRequest(workerId, CLAIM_TOKEN);
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        // 1. 두 Thread가 같은 Service Proxy의 독립 Transaction으로 거의 동시에 진입한다.
        List<Future<StartResult>> futures = List.of(
            executorService.submit(() -> startAfterBarrier(jobId, request, startBarrier)),
            executorService.submit(() -> startAfterBarrier(jobId, request, startBarrier))
        );

        // 2. Job 쓰기 잠금으로 직렬화된 두 결과를 모두 제한 시간 안에 수집한다.
        List<StartResult> results = List.of(
            futures.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            futures.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        );

        // 3. 한 호출만 생성되고 다른 호출은 같은 Attempt를 재생해야 한다.
        assertThat(results).filteredOn(StartResult::created).hasSize(1);
        assertThat(results).filteredOn(result -> !result.created()).hasSize(1);
        Long sharedAttemptId = results.get(0).response().attemptId();
        assertThat(results).extracting(result -> result.response().attemptId()).containsOnly(sharedAttemptId);
        assertThat(results).extracting(result -> result.response().attemptNo()).containsOnly(1);

        // 4. DB에도 현재 Claim의 완전한 STARTED Attempt가 정확히 한 건만 Commit돼야 한다.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM embedding_job_attempts WHERE embedding_job_id = ? AND claim_token = ?",
            Integer.class,
            jobId,
            CLAIM_TOKEN
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM embedding_job_attempts WHERE embedding_job_id = ? AND claim_token = ?",
            String.class,
            jobId,
            CLAIM_TOKEN
        )).isEqualTo("STARTED");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT worker_node_id FROM embedding_job_attempts WHERE embedding_job_id = ? AND claim_token = ?",
            Long.class,
            jobId,
            CLAIM_TOKEN
        )).isEqualTo(workerId);
    }

    /**
     * 이 통합 테스트 Context에만 고정된 고정밀 Clock을 제공해 시스템 Clock의 정밀도에 의존하지 않는다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedAttemptClock() {
            return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        }
    }

    private StartResult startAfterBarrier(
        Long jobId,
        StartEmbeddingJobAttemptRequest request,
        CyclicBarrier barrier
    ) {
        awaitBarrier(barrier);
        return embeddingJobAttemptService.start(jobId, request);
    }

    private Long insertWorker(String workerName) {
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

    private Long insertProcessingJob(Long workerId, String claimToken) {
        Long documentVersionId = insertDocumentVersion();
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
                locked_by_worker_id,
                locked_at,
                lock_expires_at,
                claim_token,
                started_at,
                created_at,
                updated_at
            )
            VALUES (?, ?, 'PROCESSING', 0, 0, 3, ?, CURRENT_TIMESTAMP,
                    TIMESTAMP '2099-01-01 00:00:00', ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentVersionId, embeddingModelId, workerId, claimToken);
    }

    private Long insertDocumentVersion() {
        String suffix = UUID.randomUUID().toString();
        Long userId = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, name, status, created_at, updated_at)
            VALUES (?, 'password-hash', 'Attempt Test User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, "attempt-" + suffix + "@example.com");
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
            VALUES (?, 'Attempt Test Document', 'TXT', 'UPLOAD', 'UPLOADED', 'PRIVATE',
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
            VALUES (?, 1, 'Attempt Test Version', 'UPLOADED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, documentId, userId);
    }

    private Long insertAttempt(Long jobId, Long workerId, int attemptNo, String claimToken) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO embedding_job_attempts (
                embedding_job_id,
                worker_node_id,
                attempt_no,
                claim_token,
                status,
                started_at,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, 'STARTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            RETURNING id
            """, Long.class, jobId, workerId, attemptNo, claimToken);
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transactionTemplate.execute(status -> work.get());
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Job Attempt 동시성 테스트 신호를 제한 시간 안에 받지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Job Attempt 동시성 테스트 대기 중 Thread가 중단되었습니다.", exception);
        }
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Job Attempt 동시성 테스트 Barrier 대기 중 Thread가 중단되었습니다.", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("Job Attempt 동시성 테스트 Barrier가 제한 시간 안에 완료되지 않았습니다.", exception);
        }
    }
}
