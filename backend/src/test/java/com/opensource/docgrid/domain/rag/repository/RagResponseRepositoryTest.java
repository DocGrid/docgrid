package com.opensource.docgrid.domain.rag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.embedding.entity.EmbeddingModel;
import com.opensource.docgrid.domain.embedding.fixture.EmbeddingModelFixture;
import com.opensource.docgrid.domain.embedding.repository.EmbeddingModelRepository;
import com.opensource.docgrid.domain.rag.entity.RagResponse;
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
 * RagJobTimeoutSweeper(#286)/RagJobWorker(#288)가 의존하는 쿼리들을 실제 PostgreSQL
 * Repository 계층에서 검증한다. {@code forceFailIfProcessing()}/{@code
 * completeSuccessIfOwned()} 둘 다 "이미 다른 경로가 먼저 끝낸 job은 절대 덮어쓰지
 * 않는다"는 조건부 UPDATE 정합성이 핵심인데, 이건 Mockito 단위 테스트로는 증명할 수 없고
 * 실제 SQL이 실행되는 이 계층에서만 검증할 수 있다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("RagResponseRepository 테스트")
class RagResponseRepositoryTest {

    @Autowired
    private RagResponseRepository ragResponseRepository;

    @Autowired
    private SearchQueryRepository searchQueryRepository;

    @Autowired
    private SearchConversationRepository searchConversationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmbeddingModelRepository embeddingModelRepository;

    @Test
    @DisplayName("forceFailIfProcessing: PROCESSING인 job은 FAILED로 강제 종료되고 영향받은 행이 1건이다")
    void forceFailIfProcessing_processingJob_updatesToFailedAndReturnsOne() {
        RagResponse job = saveRagResponse(ResultStatus.PROCESSING);

        int updated = ragResponseRepository.forceFailIfProcessing(job.getId(), "fallback 답변", "타임아웃");

        assertThat(updated).isEqualTo(1);
        RagResponse reloaded = ragResponseRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ResultStatus.FAILED);
        assertThat(reloaded.getAnswerText()).isEqualTo("fallback 답변");
        assertThat(reloaded.getErrorMessage()).isEqualTo("타임아웃");
    }

    @Test
    @DisplayName("forceFailIfProcessing: 이미 SUCCESS로 끝난 job은 덮어쓰지 않고 영향받은 행이 0건이다")
    void forceFailIfProcessing_alreadySucceededJob_doesNotOverwriteAndReturnsZero() {
        RagResponse job = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        job.markClaimed(now, "app-a", "token-a", now.plusSeconds(20));
        ragResponseRepository.save(job);
        ragResponseRepository.completeSuccessIfOwned(
            job.getId(), "token-a", now.plusSeconds(1), "실제 답변", "qwen2.5:7b", 100, 20, 900
        );

        // RagJobWorker가 이 순간 이미 SUCCESS로 커밋한 상황을 재현한다 — 스위퍼의 강제 종료는
        // 이 시점 이후 실행돼도 status 조건이 안 맞아 아무것도 바꾸면 안 된다.
        int updated = ragResponseRepository.forceFailIfProcessing(job.getId(), "fallback 답변", "타임아웃");

        assertThat(updated).isEqualTo(0);
        RagResponse reloaded = ragResponseRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(reloaded.getAnswerText()).isEqualTo("실제 답변");
    }

    @Test
    @DisplayName("completeSuccessIfOwned: 현재 token의 PROCESSING 작업만 SUCCESS로 확정한다")
    void completeSuccessIfOwned_processingJob_updatesToSuccessAndReturnsOne() {
        RagResponse job = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        job.markClaimed(now, "app-a", "token-a", now.plusSeconds(20));
        ragResponseRepository.save(job);

        int updated = ragResponseRepository.completeSuccessIfOwned(
            job.getId(), "token-a", now.plusSeconds(1), "실제 답변", "qwen2.5:7b", 100, 20, 900);

        assertThat(updated).isEqualTo(1);
        RagResponse reloaded = ragResponseRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(reloaded.getAnswerText()).isEqualTo("실제 답변");
        assertThat(reloaded.getLlmModelName()).isEqualTo("qwen2.5:7b");
    }

    @Test
    @DisplayName("completeSuccessIfOwned: 스위퍼가 FAILED로 종료한 job은 덮어쓰지 않는다")
    void completeSuccessIfOwned_alreadyTimedOutJob_doesNotOverwriteAndReturnsZero() {
        RagResponse job = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        job.markClaimed(now, "app-a", "token-a", now.plusSeconds(20));
        ragResponseRepository.save(job);
        ragResponseRepository.forceFailIfProcessing(job.getId(), "fallback 답변", "타임아웃");

        // RagJobTimeoutSweeper가 이 순간 이미 FAILED로 확정한 상황을 재현한다 — Worker가 뒤늦게
        // 완료 처리를 시도해도(#288) status 조건이 안 맞아 아무것도 바꾸면 안 된다.
        int updated = ragResponseRepository.completeSuccessIfOwned(
            job.getId(), "token-a", now.plusSeconds(1), "실제 답변", "qwen2.5:7b", 100, 20, 900);

        assertThat(updated).isEqualTo(0);
        RagResponse reloaded = ragResponseRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ResultStatus.FAILED);
        assertThat(reloaded.getAnswerText()).isEqualTo("fallback 답변");
    }

    @Test
    @DisplayName("findNextUnclaimedProcessingForUpdate: claim 안 된 PROCESSING만 찾고, claim된 것과 다른 상태는 제외한다")
    void findNextUnclaimedProcessingForUpdate_filtersOnClaimedAtAndStatus() {
        RagResponse unclaimed = saveRagResponse(ResultStatus.PROCESSING);
        RagResponse alreadyClaimed = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        alreadyClaimed.markClaimed(now, "app-a", "token-a", now.plusSeconds(20));
        ragResponseRepository.save(alreadyClaimed);
        saveRagResponse(ResultStatus.SUCCESS);

        RagResponse found = ragResponseRepository.findNextUnclaimedProcessingForUpdate(LocalDateTime.now()).orElseThrow();

        assertThat(found.getId()).isEqualTo(unclaimed.getId());
    }

    @Test
    @DisplayName("findNextUnclaimedProcessingForUpdate: claim 가능한 job이 없으면 빈 값을 반환한다")
    void findNextUnclaimedProcessingForUpdate_noCandidates_returnsEmpty() {
        RagResponse claimed = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        claimed.markClaimed(now, "app-a", "token-a", now.plusSeconds(20));
        ragResponseRepository.save(claimed);

        assertThat(ragResponseRepository.findNextUnclaimedProcessingForUpdate(LocalDateTime.now())).isEmpty();
    }

    @Test
    @DisplayName("유효한 lease는 다른 앱이 시작해도 재선점되지 않고, 만료 뒤에는 재선점된다")
    void leaseExpiration_controlsReclaim() {
        RagResponse claimed = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        claimed.markClaimed(now, "app-a", "token-a", now.plusSeconds(20));
        ragResponseRepository.save(claimed);

        assertThat(ragResponseRepository.findNextUnclaimedProcessingForUpdate(now.plusSeconds(10))).isEmpty();
        assertThat(ragResponseRepository.findNextUnclaimedProcessingForUpdate(now.plusSeconds(21)))
            .get().extracting(RagResponse::getId).isEqualTo(claimed.getId());
    }

    @Test
    @DisplayName("옛 토큰의 완료는 0행, 현재 토큰의 완료는 1행이다")
    void completion_requiresCurrentToken() {
        RagResponse claimed = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        claimed.markClaimed(now, "app-b", "token-b", now.plusSeconds(20));
        ragResponseRepository.save(claimed);

        int oldUpdate = ragResponseRepository.completeSuccessIfOwned(
            claimed.getId(), "token-a", now.plusSeconds(1), "옛 답변", "test", 1, 1, 1
        );
        int currentUpdate = ragResponseRepository.completeSuccessIfOwned(
            claimed.getId(), "token-b", now.plusSeconds(1), "새 답변", "test", 1, 1, 1
        );

        assertThat(oldUpdate).isZero();
        assertThat(currentUpdate).isEqualTo(1);
        assertThat(ragResponseRepository.findById(claimed.getId()).orElseThrow().getAnswerText()).isEqualTo("새 답변");
    }

    @Test
    @DisplayName("만료된 lease는 옛 token이 남아 있어도 완료할 수 없다")
    void expiredLease_rejectsCompletion() {
        RagResponse claimed = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        claimed.markClaimed(now, "app-a", "token-a", now.plusSeconds(20));
        ragResponseRepository.save(claimed);

        int updated = ragResponseRepository.completeSuccessIfOwned(
            claimed.getId(), "token-a", now.plusSeconds(21), "늦은 답변", "test", 1, 1, 1
        );

        assertThat(updated).isZero();
        assertThat(ragResponseRepository.findById(claimed.getId()).orElseThrow().getStatus())
            .isEqualTo(ResultStatus.PROCESSING);
    }

    @Test
    @DisplayName("현재 소유자만 lease를 연장하고 이전 token은 연장하지 못한다")
    void renewClaimLease_requiresCurrentOwnerAndToken() {
        RagResponse claimed = saveRagResponse(ResultStatus.PROCESSING);
        LocalDateTime now = LocalDateTime.now();
        claimed.markClaimed(now, "app-b", "token-b", now.plusSeconds(20));
        ragResponseRepository.save(claimed);

        int oldOwner = ragResponseRepository.renewClaimLease(
            claimed.getId(), "app-a", "token-a", now.plusSeconds(5), now.plusSeconds(30)
        );
        int currentOwner = ragResponseRepository.renewClaimLease(
            claimed.getId(), "app-b", "token-b", now.plusSeconds(5), now.plusSeconds(30)
        );

        assertThat(oldOwner).isZero();
        assertThat(currentOwner).isEqualTo(1);
        assertThat(ragResponseRepository.findById(claimed.getId()).orElseThrow().getClaimLeaseUntil())
            .isEqualTo(now.plusSeconds(30));
    }

    @Test
    @DisplayName("findByStatusAndCreatedAtBefore: cutoff 이전에 생성된 PROCESSING만 찾고, 상태가 다른 job은 제외한다")
    void findByStatusAndCreatedAtBefore_filtersOnStatusAndCreatedAt() {
        LocalDateTime beforeAnyCreation = LocalDateTime.now();
        RagResponse processingJob = saveRagResponse(ResultStatus.PROCESSING);
        saveRagResponse(ResultStatus.SUCCESS);

        // cutoff가 두 job이 생성되기 전 시점이면(=아직 아무 job도 이 시간만큼 오래 기다리지 않음)
        // 아무것도 찾지 못해야 한다.
        assertThat(ragResponseRepository.findByStatusAndCreatedAtBefore(ResultStatus.PROCESSING, beforeAnyCreation))
            .isEmpty();

        LocalDateTime afterCreation = LocalDateTime.now();
        List<RagResponse> result =
            ragResponseRepository.findByStatusAndCreatedAtBefore(ResultStatus.PROCESSING, afterCreation);

        assertThat(result).extracting(RagResponse::getId).containsExactly(processingJob.getId());
    }

    private RagResponse saveRagResponse(ResultStatus status) {
        User user = userRepository.save(User.builder()
            .email("rag-repo-test-" + System.nanoTime() + "@test.local")
            .passwordHash("x")
            .name("RAG저장소테스트유저")
            .status(UserStatus.ACTIVE)
            .build());
        EmbeddingModel model = embeddingModelRepository.save(
            EmbeddingModelFixture.createModel("rag-repo-test-" + System.nanoTime(), false, false));
        SearchConversation conversation = searchConversationRepository.save(SearchConversation.builder()
            .user(user)
            .title("테스트 질문")
            .lastMessageAt(LocalDateTime.now())
            .build());
        SearchQuery query = searchQueryRepository.save(SearchQuery.builder()
            .user(user)
            .conversation(conversation)
            .queryText("테스트 질문")
            .queryEmbeddingModel(model)
            .queryVector(new float[1024])
            .searchType(SearchType.VECTOR)
            .topK(5)
            .status(ResultStatus.SUCCESS)
            .build());
        return ragResponseRepository.save(RagResponse.builder()
            .query(query)
            .promptText("프롬프트")
            .status(status)
            .build());
    }
}
