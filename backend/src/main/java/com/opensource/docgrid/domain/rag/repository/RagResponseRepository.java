package com.opensource.docgrid.domain.rag.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.rag.entity.RagResponse;
import com.opensource.docgrid.domain.search.dto.ConversationContext;
import com.opensource.docgrid.domain.search.dto.ConversationRagResponseProjection;
import com.opensource.docgrid.domain.search.enums.ResultStatus;

/**
 * RagResponse 엔티티에 대한 JPA Repository.
 */
public interface RagResponseRepository extends JpaRepository<RagResponse, Long> {

    /**
     * PROCESSING 중 아직 아무 Worker도 집지 않은(claim 안 된) 것 하나를 골라 행 잠금을 건다(#340).
     * {@code claimed_at IS NULL} 조건이 "대기 중"과 "이미 처리 중"을 구분하는 유일한 신호다 —
     * status만으로는 둘 다 PROCESSING이라 구분이 안 된다. {@code FOR UPDATE SKIP LOCKED}로
     * 여러 Worker가 동시에 이 쿼리를 날려도 이미 잠긴 행은 건너뛰고 그다음 미잠금 행을 잡아온다.
     * {@code created_at}이 같은 밀리초를 공유할 수 있는 동시 접수 상황을 대비해 {@code id}를
     * 2차 정렬 기준으로 둔다. {@link RagResponseClaimService}가 이 메서드로 잠근 행을 같은 짧은
     * 트랜잭션 안에서 즉시 {@link RagResponse#markClaimed}로 확정하고 커밋해, 락을 오래 들고
     * 있지 않는다({@code embedding_jobs}의 claim 패턴과 동일).
     */
    @Query(value = """
        SELECT * FROM rag_responses
        WHERE status = 'PROCESSING' AND claimed_at IS NULL
        ORDER BY created_at ASC, id ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<RagResponse> findNextUnclaimedProcessingForUpdate();

    /**
     * claim된 job을 실제로 처리하는 Worker 스레드가 쓰는 조회. {@code query}/{@code query.user}를
     * {@link EntityGraph}로 미리 fetch해, 처리가 끝난 뒤(WebSocket push 시점) 트랜잭션 밖에서
     * {@code job.getQuery().getUser().getEmail()}에 접근해도 {@code LazyInitializationException}이
     * 나지 않게 한다. 기존 {@code findById}는 그대로 두고 이름을 다르게 둔 이유는, {@code RagFacade}가
     * 이미 쓰고 있는 평범한 {@code findById(jobId)} 호출의 동작을 이번 변경으로 건드리지 않기 위함이다.
     */
    @EntityGraph(attributePaths = {"query", "query.user"})
    Optional<RagResponse> findWithQueryAndUserById(Long id);

    /** 특정 검색 요청(queryId)에 대한 RAG 답변을 찾는다. GET /search/{queryId} 재조회에 쓰인다. */
    Optional<RagResponse> findByQuery_Id(Long queryId);

    /** 여러 대화 Turn의 RAG 상태와 답변을 queryId 기준으로 한 번에 조회한다. */
    @Query("""
        SELECT new com.opensource.docgrid.domain.search.dto.ConversationRagResponseProjection(
            r.query.id, r.status, r.answerText
        )
        FROM RagResponse r
        WHERE r.query.id IN :queryIds
        """)
    List<ConversationRagResponseProjection> findConversationDetailResponses(
        @Param("queryIds") List<Long> queryIds
    );

    /**
     * 후속 질문 프롬프트에 사용할 확정 답변만 최근순으로 제한 조회한다.
     * 현재 생성 중인 Query는 excludedQueryId로 제외해 자기 자신을 문맥으로 다시 읽지 않게 한다.
     */
    @Query("""
        SELECT new com.opensource.docgrid.domain.search.dto.ConversationContext(q.queryText, r.answerText)
        FROM RagResponse r
        JOIN r.query q
        WHERE q.conversation.id = :conversationId
          AND (:excludedQueryId IS NULL OR q.id <> :excludedQueryId)
          AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.SUCCESS
          AND r.answerText IS NOT NULL
        ORDER BY q.createdAt DESC
        """)
    List<ConversationContext> findRecentConversationContext(
        @Param("conversationId") Long conversationId,
        @Param("excludedQueryId") Long excludedQueryId,
        Pageable pageable
    );

    /**
     * 주어진 상태(보통 PROCESSING)로 threshold 이전부터 남아있는 job들을 찾는다 —
     * RagJobTimeoutSweeper가 "얼마나 오래 대기 중인지"를 별도 컬럼 없이 {@code createdAt}
     * 기준으로 판단할 때 쓴다. {@link EntityGraph}로 {@code query}/{@code query.user}를 미리
     * fetch하는 이유는 {@code findFirstByStatusOrderByCreatedAtAsc}와 동일하다 — 스위퍼도
     * WebSocket 알림을 보내려면 트랜잭션 밖에서 {@code query.user.email}에 접근해야 한다.
     */
    @EntityGraph(attributePaths = {"query", "query.user"})
    List<RagResponse> findByStatusAndCreatedAtBefore(ResultStatus status, LocalDateTime threshold);

    /**
     * PROCESSING 상태인 job을 FAILED로 강제 종료한다. {@code WHERE ... AND status = PROCESSING}
     * 조건 덕분에, 이 UPDATE가 실행되는 순간 RagJobWorker가 이미 다른 트랜잭션에서 이 job을
     * SUCCESS/FAILED로 먼저 확정했다면 영향받은 행이 0건이 된다 — 이 저장소엔 {@code @Version}
     * 필드가 없어 엔티티를 그대로 불러와 save()하면 나중 쓰기가 그냥 이기는데, 그 대신 이 조건부
     * UPDATE로 "이미 끝난 job을 덮어쓰는" 경합을 막는다. 반환값(영향받은 행 수)으로 호출자가
     * 실제로 강제 종료가 일어났는지 판단한다.
     *
     * <p>{@code RagJobTimeoutSweeper}뿐 아니라 {@code RagResponseCommandService.completeFailed()}
     * (RagJobWorker가 Ollama 호출 실패를 처리하는 정상 경로)도 이 메서드를 그대로 재사용한다 —
     * 둘 다 "PROCESSING인 job을 FAILED + 문구로 확정한다"는 동일한 SQL이 필요하고, 반대로
     * RagJobTimeoutSweeper가 먼저 이 job을 확정해버렸다면 RagJobWorker 쪽 시도도 똑같이
     * 무시돼야 하기 때문이다(#288).
     *
     * <p>{@code clearAutomatically}: 벌크 UPDATE는 영속성 컨텍스트를 거치지 않고 DB에 직접
     * 실행되므로, 같은 트랜잭션에서 이 job 엔티티를 이미 로딩해둔 상태라면 그 캐시된 인스턴스가
     * 여전히 갱신 전 값을 들고 있다 — 이후 같은 트랜잭션에서 다시 조회해도 DB가 아니라 그 캐시를
     * 돌려줘 최신 상태를 못 본다. {@code clearAutomatically = true}로 UPDATE 직후 영속성
     * 컨텍스트를 비워 이 문제를 막는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED, "
        + "r.answerText = :answerText, r.errorMessage = :errorMessage "
        + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING")
    int forceFailIfProcessing(@Param("id") Long id, @Param("answerText") String answerText,
                               @Param("errorMessage") String errorMessage);

    /**
     * PROCESSING 상태인 job을 SUCCESS + 생성 결과로 확정한다. {@link #forceFailIfProcessing}과
     * 대칭되는 목적이다 — RagJobTimeoutSweeper가 이 job을 먼저 FAILED로 강제 종료했다면,
     * RagJobWorker의 뒤늦은 정상 완료 시도가 그 결과를 조건 없이 덮어써버리는 경합(#288)을
     * 막는다. {@code WHERE ... AND status = PROCESSING} 조건 덕분에, 스위퍼가 먼저 확정해
     * 이 UPDATE 시점에 status가 이미 FAILED라면 영향받은 행이 0건이 된다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.SUCCESS, "
        + "r.answerText = :answerText, r.llmModelName = :llmModelName, "
        + "r.inputTokenCount = :inputTokenCount, r.outputTokenCount = :outputTokenCount, "
        + "r.latencyMs = :latencyMs "
        + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING")
    int completeSuccessIfProcessing(@Param("id") Long id, @Param("answerText") String answerText,
                                     @Param("llmModelName") String llmModelName,
                                     @Param("inputTokenCount") Integer inputTokenCount,
                                     @Param("outputTokenCount") Integer outputTokenCount,
                                     @Param("latencyMs") Integer latencyMs);
}
