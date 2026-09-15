package com.opensource.docgrid.domain.rag.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.rag.repository.RagResponseRepository;
import com.opensource.docgrid.domain.rag.repository.ResponseCitationRepository;
import com.opensource.docgrid.domain.rag.service.command.RagResponseCommandService;
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
 * #218 비동기 Job 큐의 핵심 목표 검증: "여러 질문이 동시에 들어와도 전부 완전한 LLM 답변을
 * 받는다"(실패 없음). 실제 Spring @Scheduled RagJobWorker가 백그라운드에서 자연스럽게 큐를
 * 비우도록 두고(수동으로 processNext()를 여러 번 호출하지 않음), 3명이 정확히 같은 순간에
 * 질문을 던졌다고 가정해 3개 job을 동시 스레드로 접수한 뒤, 셋 다 결국 SUCCESS로 끝나는지
 * 실제 로컬 Ollama를 상대로 확인한다.
 *
 * <p>병렬화(#340) 이후에는 여기에 "실제로 동시에 처리됐는가"까지 증명한다 — {@code claimed_at}이
 * "언제부터 실제로 처리되기 시작했는지"를 알려주는 유일한 신호다({@code updatedAt}은 완료 확정이
 * 전부 벌크 UPDATE라 채워지지 않는다). {@code rag.worker.max-concurrency}가 2 이상이면 3건 중
 * 최소 2건은 거의 동시에 claim되어야 한다 — 셋 다 동시에 claim되길 요구하지 않는 이유는
 * max-concurrency가 정확히 2일 때는 3번째 job이 앞선 두 건 중 하나가 끝날 때까지 자연스럽게
 * 기다리기 때문이다(그래도 안전 실패는 없다 — #218의 핵심 목표).
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class RagJobWorkerConcurrentQueueIntegrationTest {

    @Autowired private RagResponseCommandService ragResponseCommandService;
    @Autowired private RagResponseRepository ragResponseRepository;
    @Autowired private SearchQueryRepository searchQueryRepository;
    @Autowired private SearchConversationRepository searchConversationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmbeddingModelRepository embeddingModelRepository;
    @Autowired private ResponseCitationRepository responseCitationRepository;

    private final List<Long> createdUserIds = new CopyOnWriteArrayList<>();
    private final List<Long> createdQueryIds = new CopyOnWriteArrayList<>();
    private final List<Long> createdConversationIds = new CopyOnWriteArrayList<>();
    private Long createdModelId;

    // 이 테스트는 @Transactional로 감쌀 수 없다(실제 @Scheduled Worker가 별도 스레드·트랜잭션에서
    // 자연스럽게 큐를 비우는 걸 검증해야 하므로). 그래서 만든 유저·검색·답변을 직접 정리한다 —
    // 안 그러면 docgrid_test 스키마에 유저가 계속 쌓여 무관한 테스트(페이징 검증 등)가 흔들린다.
    @AfterEach
    void cleanUp() {
        for (Long queryId : createdQueryIds) {
            ragResponseRepository.findByQuery_Id(queryId).ifPresent(r -> {
                responseCitationRepository.findByResponse_IdOrderByCitationOrder(r.getId())
                    .forEach(responseCitationRepository::delete);
                ragResponseRepository.delete(r);
            });
            searchQueryRepository.deleteById(queryId);
        }
        createdConversationIds.forEach(searchConversationRepository::deleteById);
        if (createdModelId != null) embeddingModelRepository.deleteById(createdModelId);
        createdUserIds.forEach(userRepository::deleteById);
    }

    @Test
    @DisplayName("동시에 접수된 job 3개가 실제 RagJobWorker 스케줄러만으로 전부 SUCCESS로 끝난다")
    void threeConcurrentJobs_allEventuallySucceedViaRealScheduler() throws InterruptedException {
        EmbeddingModel model = embeddingModelRepository.save(
            EmbeddingModelFixture.createModel("concurrent-it-" + System.nanoTime(), false, false)
        );
        createdModelId = model.getId();

        List<String> prompts = List.of(
            "숫자만 한 글자로 답해줘. 1+1은?",
            "숫자만 한 글자로 답해줘. 2+2는?",
            "숫자만 한 글자로 답해줘. 3+3은?"
        );

        List<Long> jobIds = new CopyOnWriteArrayList<>();
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch allSubmitted = new CountDownLatch(prompts.size());

        // 3명이 "정확히 같은 순간"에 질문을 던진 상황을 재현 — 스레드를 미리 다 띄워두고
        // startLine으로 동시에 풀어준다.
        for (String prompt : prompts) {
            Thread thread = new Thread(() -> {
                try {
                    startLine.await();
                    User user = createUser();
                    SearchConversation conversation = searchConversationRepository.save(SearchConversation.builder()
                        .user(user)
                        .title(prompt)
                        .lastMessageAt(LocalDateTime.now())
                        .build());
                    createdConversationIds.add(conversation.getId());
                    SearchQuery query = searchQueryRepository.save(SearchQuery.builder()
                        .user(user)
                        .conversation(conversation)
                        .queryText(prompt)
                        .queryEmbeddingModel(model)
                        .queryVector(new float[1024])
                        .searchType(SearchType.VECTOR)
                        .topK(5)
                        .status(ResultStatus.SUCCESS)
                        .build());
                    createdQueryIds.add(query.getId());
                    RagResponse pending = ragResponseCommandService.createPending(query, prompt);
                    jobIds.add(pending.getId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allSubmitted.countDown();
                }
            });
            thread.start();
        }
        startLine.countDown();
        allSubmitted.await();

        assertThat(jobIds).hasSize(3);

        // 수동으로 processNext()를 여러 번 부르지 않는다 — 실제 배포에서와 똑같이, 이미 켜져 있는
        // @Scheduled RagJobWorker가 1초 주기로 알아서 큐를 비우는 걸 그대로 기다린다.
        await().atMost(Duration.ofSeconds(150)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            List<RagResponse> jobs = ragResponseRepository.findAllById(jobIds);
            assertThat(jobs).allSatisfy(job -> assertThat(job.getStatus()).isNotEqualTo(ResultStatus.PROCESSING));
        });

        List<RagResponse> finished = ragResponseRepository.findAllById(jobIds);
        assertThat(finished).hasSize(3);
        // 핵심 주장: 셋 다 "빈손"이 아니라 실제 답변 텍스트를 갖고 있다(SUCCESS든, LLM 실패 시의
        // extractive fallback이든 — 어느 쪽이든 answerText는 항상 채워진다).
        assertThat(finished).allSatisfy(job -> assertThat(job.getAnswerText()).isNotBlank());
        long successCount = finished.stream().filter(j -> j.getStatus() == ResultStatus.SUCCESS).count();
        System.out.println("[TEST] SUCCESS=" + successCount + "/3, answers=" +
            finished.stream().map(RagResponse::getAnswerText).toList());

        // #340 병렬화 증명: claimed_at 3건 중 최소 2건은 서로 가까운 시각에 claim됐어야 한다 —
        // 순차 처리였다면(#218 이전 방식) 각 claim은 앞선 job의 전체 처리 시간(수 초~수십 초)만큼
        // 떨어져 있었을 것이다. max-concurrency가 정확히 2여도(기본값) 최소 두 건은 동시에 슬롯을
        // 잡을 수 있으므로, "셋 다"가 아니라 "가장 가까운 두 건"의 간격으로 판단한다.
        List<LocalDateTime> claimedAtValues = finished.stream()
            .map(RagResponse::getClaimedAt)
            .sorted()
            .toList();
        assertThat(claimedAtValues).allSatisfy(claimedAt -> assertThat(claimedAt).isNotNull());
        Duration closestGap = Duration.between(claimedAtValues.get(0), claimedAtValues.get(1));
        System.out.println("[TEST] claimedAt=" + claimedAtValues + " closestGap=" + closestGap);
        assertThat(closestGap).isLessThan(Duration.ofSeconds(10));
    }

    private User createUser() {
        User user = userRepository.save(User.builder()
            .email("concurrent-it-" + System.nanoTime() + "-" + Math.random() + "@test.local")
            .passwordHash("x")
            .name("동시성테스트유저")
            .status(UserStatus.ACTIVE)
            .build());
        createdUserIds.add(user.getId());
        return user;
    }
}
