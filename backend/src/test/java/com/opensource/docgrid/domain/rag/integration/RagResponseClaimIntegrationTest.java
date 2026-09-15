package com.opensource.docgrid.domain.rag.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.service.command.RagResponseClaimService;
import com.opensource.docgrid.domain.search.entity.SearchConversation;
import com.opensource.docgrid.domain.search.entity.SearchQuery;
import com.opensource.docgrid.domain.search.enums.ResultStatus;
import com.opensource.docgrid.domain.search.enums.SearchType;
import com.opensource.docgrid.domain.search.repository.SearchConversationRepository;
import com.opensource.docgrid.domain.search.repository.SearchQueryRepository;
import com.opensource.docgrid.domain.user.entity.User;
import com.opensource.docgrid.domain.user.enums.UserStatus;
import com.opensource.docgrid.domain.user.repository.UserRepository;

/**
 * 실제 PostgreSQL에서 RAG Job Claim의 행 잠금·동시 claim 불변식을 검증하는 통합 테스트 (#340).
 *
 * <p>{@code EmbeddingJobClaimIntegrationTest}와 같은 방식으로, 서로 다른 Thread와
 * {@code REQUIRES_NEW} Transaction을 사용해 단일 Persistence Context의 순차 호출로는 재현할 수
 * 없는 {@code FOR UPDATE SKIP LOCKED} 경쟁을 검증한다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RagResponse Claim DB 동시성 통합 테스트")
class RagResponseClaimIntegrationTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RagResponseRepository ragResponseRepository;
    @Autowired private RagResponseClaimService ragResponseClaimService;
    @Autowired private SearchQueryRepository searchQueryRepository;
    @Autowired private SearchConversationRepository searchConversationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmbeddingModelRepository embeddingModelRepository;

    private ExecutorService executorService;
    private final AtomicInteger threadSequence = new AtomicInteger();
    private final List<Long> createdUserIds = new CopyOnWriteArrayList<>();
    private final List<Long> createdQueryIds = new CopyOnWriteArrayList<>();
    private final List<Long> createdConversationIds = new CopyOnWriteArrayList<>();
    private Long createdModelId;

    @BeforeAll
    void createExecutor() {
        executorService = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("rag-claim-test-" + threadSequence.incrementAndGet());
            return thread;
        });
    }

    @AfterAll
    void shutdownExecutor() throws InterruptedException {
        executorService.shutdownNow();
        assertThat(executorService.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    }

    // 이 클래스의 모든 테스트가 REQUIRES_NEW로 자체 트랜잭션을 커밋하므로(그래야 다른 Thread에서
    // 그 결과가 보인다) 자동 롤백에 기댈 수 없다 — 만든 데이터를 직접 정리한다.
    @AfterEach
    void cleanUp() {
        for (Long queryId : createdQueryIds) {
            ragResponseRepository.findByQuery_Id(queryId).ifPresent(ragResponseRepository::delete);
            searchQueryRepository.deleteById(queryId);
        }
        createdQueryIds.clear();
        createdConversationIds.forEach(searchConversationRepository::deleteById);
        createdConversationIds.clear();
        if (createdModelId != null) {
            embeddingModelRepository.deleteById(createdModelId);
            createdModelId = null;
        }
        createdUserIds.forEach(userRepository::deleteById);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 행은 기다리지 않고 다음 미잠금 행을 선택한다")
    void findNextUnclaimedProcessingForUpdate_skipsLockedRow() throws Exception {
        Long firstJobId = createPendingJob();
        Long secondJobId = createPendingJob();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        // 1. 첫 번째 Transaction이 가장 오래된 job(firstJobId)의 행 잠금을 잡은 채 커밋을 지연한다.
        Future<Long> lockHolder = executorService.submit(() -> inNewTransaction(() -> {
            Long selectedId = ragResponseRepository.findNextUnclaimedProcessingForUpdate()
                .orElseThrow().getId();
            rowLocked.countDown();
            awaitLatch(releaseLock);
            return selectedId;
        }));

        // 2. 첫 번째 행이 실제로 잠긴 뒤에만 두 번째 Transaction을 시작해 경쟁 조건을 확정한다.
        assertThat(rowLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        // 3. 두 번째 Transaction은 잠금 해제를 기다리지 않고 다음 미잠금 job을 선택해야 한다.
        Future<Long> skipLockedReader = executorService.submit(() -> inNewTransaction(() ->
            ragResponseRepository.findNextUnclaimedProcessingForUpdate().orElseThrow().getId()
        ));

        try {
            assertThat(skipLockedReader.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(secondJobId);
        } finally {
            releaseLock.countDown();
        }

        assertThat(lockHolder.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(firstJobId);
    }

    @Test
    @DisplayName("두 Worker가 동시에 같은 job을 claim해도 한쪽만 소유권을 얻는다")
    void claim_allowsExactlyOneConcurrentOwner() throws Exception {
        Long jobId = createPendingJob();
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        List<Future<Optional<Long>>> attempts = List.of(
            executorService.submit(() -> claimAfterBarrier(startBarrier)),
            executorService.submit(() -> claimAfterBarrier(startBarrier))
        );

        List<Optional<Long>> results = List.of(
            attempts.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            attempts.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        );

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        assertThat(results).filteredOn(Optional::isEmpty).hasSize(1);
        assertThat(results.stream().flatMap(Optional::stream).findFirst()).contains(jobId);

        RagResponse persisted = ragResponseRepository.findById(jobId).orElseThrow();
        assertThat(persisted.getClaimedAt()).isNotNull();
    }

    private Optional<Long> claimAfterBarrier(CyclicBarrier barrier) {
        awaitBarrier(barrier);
        return inNewTransaction(() -> ragResponseClaimService.claimNext());
    }

    private Long createPendingJob() {
        return inNewTransaction(() -> {
            User user = userRepository.save(User.builder()
                .email("rag-claim-it-" + System.nanoTime() + "@test.local")
                .passwordHash("x")
                .name("RAG Claim 테스트 유저")
                .status(UserStatus.ACTIVE)
                .build());
            createdUserIds.add(user.getId());

            if (createdModelId == null) {
                EmbeddingModel model = embeddingModelRepository.save(
                    EmbeddingModelFixture.createModel("rag-claim-it-" + System.nanoTime(), false, false)
                );
                createdModelId = model.getId();
            }
            EmbeddingModel model = embeddingModelRepository.findById(createdModelId).orElseThrow();

            SearchConversation conversation = searchConversationRepository.save(SearchConversation.builder()
                .user(user)
                .title("RAG Claim 테스트 질문")
                .lastMessageAt(LocalDateTime.now())
                .build());
            createdConversationIds.add(conversation.getId());

            SearchQuery query = searchQueryRepository.save(SearchQuery.builder()
                .user(user)
                .conversation(conversation)
                .queryText("RAG Claim 테스트 질문")
                .queryEmbeddingModel(model)
                .queryVector(new float[1024])
                .searchType(SearchType.VECTOR)
                .topK(5)
                .status(ResultStatus.SUCCESS)
                .build());
            createdQueryIds.add(query.getId());

            RagResponse pending = ragResponseRepository.save(RagResponse.builder()
                .query(query)
                .promptText("RAG Claim 테스트 프롬프트")
                .status(ResultStatus.PROCESSING)
                .build());
            return pending.getId();
        });
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
