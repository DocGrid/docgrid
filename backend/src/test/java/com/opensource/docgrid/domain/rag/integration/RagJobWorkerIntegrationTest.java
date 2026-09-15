package com.opensource.docgrid.domain.rag.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDateTime;

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
import com.opensource.docgrid.domain.rag.service.RagJobWorker;
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
 * claim 단계(RagResponseClaimService)로 꺼낸 job이 detached 상태라, RagFacade.processJob()에 그
 * 인스턴스를 그대로 넘기면(원래 #218 당시 그랬듯) 완료 처리가 DB에 반영되지 않는(=영원히
 * PROCESSING으로 남는) 실사용 버그가 있었다. 지금은 완료 처리 자체가 조건부 UPDATE(#288)라
 * detached 상태 여부와 무관하게 반영되지만, processJob()이 여전히 jobId만 받아 자기 트랜잭션에서
 * 다시 조회하는 설계를 유지하는지는 이 테스트로 계속 검증한다. 이 테스트는 이걸 Mockito 목이
 * 아니라 실제 트랜잭션 경계로 재현·검증한다 — 목 기반 단위 테스트는 "메서드가 호출됐는지"만
 * 보고 "DB에 실제로 반영됐는지"는 증명하지 못한다.
 *
 * <p>병렬화(#340) 이후 {@code processNext()}는 claim만 하고 실제 처리는 전용 Executor
 * 스레드에 넘긴 뒤 즉시 반환한다 — 그래서 이 테스트도 호출 직후 동기적으로 결과를 확인하는
 * 대신, {@link RagJobWorkerConcurrentQueueIntegrationTest}가 이미 쓰는 Awaitility로 처리가
 * 끝날 때까지 기다린다.
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class RagJobWorkerIntegrationTest {

    @Autowired private RagJobWorker ragJobWorker;
    @Autowired private RagResponseCommandService ragResponseCommandService;
    @Autowired private RagResponseRepository ragResponseRepository;
    @Autowired private SearchQueryRepository searchQueryRepository;
    @Autowired private SearchConversationRepository searchConversationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmbeddingModelRepository embeddingModelRepository;
    @Autowired private ResponseCitationRepository responseCitationRepository;

    private Long createdUserId;
    private Long createdModelId;
    private Long createdQueryId;
    private Long createdConversationId;

    // 테스트 메서드를 @Transactional로 감쌀 수 없어(위 설명 참고) 자동 롤백이 안 되므로, 만든
    // 데이터를 직접 정리한다 — 안 그러면 docgrid_test 스키마에 유저가 계속 쌓여 다른 테스트
    // (예: 페이징 검증)가 이 잔여 데이터 때문에 흔들리는 사고가 난다(실제로 한 번 발생했었다).
    @AfterEach
    void cleanUp() {
        if (createdQueryId != null) {
            ragResponseRepository.findByQuery_Id(createdQueryId).ifPresent(r -> {
                responseCitationRepository.findByResponse_IdOrderByCitationOrder(r.getId())
                    .forEach(responseCitationRepository::delete);
                ragResponseRepository.delete(r);
            });
            searchQueryRepository.deleteById(createdQueryId);
        }
        if (createdConversationId != null) searchConversationRepository.deleteById(createdConversationId);
        if (createdModelId != null) embeddingModelRepository.deleteById(createdModelId);
        if (createdUserId != null) userRepository.deleteById(createdUserId);
    }

    @Test
    @DisplayName("processNext(): PROCESSING row가 detached 상태로 넘어가도 최종 상태가 DB에 실제로 반영된다")
    void processNext_persistsStatusChangeAcrossDetachedEntityBoundary() {
        // 테스트 메서드 자체를 @Transactional로 감싸지 않는다 — 그러면 아래 저장들과 processNext()
        // 내부 호출이 전부 같은 세션을 공유해버려 원래 버그(서로 다른 트랜잭션 간 detached 상태)를
        // 재현하지 못한다. 각 호출이 자기 자신의 @Transactional로 독립적인 커밋을 하도록 그대로 둔다.
        User user = userRepository.save(User.builder()
            .email("ragjobworker-it-" + System.nanoTime() + "@test.local")
            .passwordHash("x")
            .name("통합테스트유저")
            .status(UserStatus.ACTIVE)
            .build());
        createdUserId = user.getId();
        // 활성 임베딩 모델은 "동시에 1개만" 제약(uk_embedding_models_one_active_searchable)이 걸려있어,
        // seed 데이터에 이미 있는 활성 모델과 충돌하지 않도록 이 테스트 전용 비활성 모델을 새로 만든다.
        EmbeddingModel model = embeddingModelRepository.save(
            EmbeddingModelFixture.createModel("ragjobworker-it-" + System.nanoTime(), false, false)
        );
        createdModelId = model.getId();
        SearchConversation conversation = searchConversationRepository.save(SearchConversation.builder()
            .user(user)
            .title("통합 테스트 질문")
            .lastMessageAt(LocalDateTime.now())
            .build());
        createdConversationId = conversation.getId();
        SearchQuery query = searchQueryRepository.save(SearchQuery.builder()
            .user(user)
            .conversation(conversation)
            .queryText("통합 테스트 질문")
            .queryEmbeddingModel(model)
            .queryVector(new float[1024])
            .searchType(SearchType.VECTOR)
            .topK(5)
            .status(ResultStatus.SUCCESS)
            .build());
        createdQueryId = query.getId();

        RagResponse pending = ragResponseCommandService.createPending(query, "통합 테스트용 프롬프트");
        Long jobId = pending.getId();

        ragJobWorker.processNext();

        // processNext()는 claim만 하고 즉시 반환하므로(#340), 실제 Ollama 호출·완료 확정은
        // 전용 Executor 스레드에서 비동기로 이어진다 — 120초(read-timeout 90s + 여유)까지
        // 기다렸다가 확인한다. Ollama가 로컬에 떠 있지 않을 수도 있으므로 SUCCESS/FAILED 둘 다
        // 통과 조건으로 둔다 — 이 테스트가 검증하는 건 "LLM 호출 성공 여부"가 아니라 "detached
        // 상태에서도 최종 상태가 DB에 반영되는지"다.
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            RagResponse persisted = ragResponseRepository.findById(jobId).orElseThrow();
            assertThat(persisted.getStatus()).isNotEqualTo(ResultStatus.PROCESSING);
            assertThat(persisted.getAnswerText()).isNotNull();
        });
    }
}
