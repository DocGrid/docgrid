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
 *
 * <p>{@code rag_responses} 테이블은 답변 저장소이면서 동시에 RagJobWorker의 job 큐다 — 별도 큐
 * 테이블 없이 {@code status=PROCESSING} 행이 곧 대기/처리 중인 job이다. 그래서 이 Repository에는
 * 평범한 조회 외에 큐 소비를 위한 쿼리가 섞여 있다.
 *
 * <p>동시성 방침: 잠금은 job을 집는 순간({@link #findNextUnclaimedProcessingForUpdate})에만 짧게
 * 걸고, 완료 확정은 잠금 없이 {@code WHERE status = PROCESSING} 조건부 UPDATE
 * ({@link #completeSuccessIfProcessing}/{@link #forceFailIfProcessing})로 한다. 이 프로젝트엔
 * {@code @Version}이 없어 엔티티를 불러와 save()하면 나중 쓰기가 무조건 이기는데, SQL의 WHERE절이
 * "먼저 끝난 결과를 덮어쓰지 마라"를 대신한다.
 *
 * <pre>
 * 메서드                                  호출자          시점
 * findNextUnclaimedProcessingForUpdate    Claim 서비스    폴링마다 (job 집기)
 * findWithQueryAndUserById                Worker          claim 직후 (알림용 email)
 * releaseAllClaimsOnStartup               Worker          기동 1회 (죽은 claim 복구)
 * findByStatusAndCreatedAtBefore          Sweeper         15초마다 (90초 넘은 job 탐색)
 * forceFailIfProcessing                   Sweeper+Worker  FAILED 확정
 * completeSuccessIfProcessing             Worker          SUCCESS 확정
 * </pre>
 */
public interface RagResponseRepository extends JpaRepository<RagResponse, Long> {

    /**
     * 대기 중인 job 중 가장 오래된 것 하나를 골라 행 잠금을 걸고 가져온다(#340). 절별 의미:
     * <ul>
     * <li>{@code status = 'PROCESSING' AND claimed_at IS NULL} — 둘이 합쳐져야 "대기 중"이다.
     *     status만 보면 처리 중인 것도 잡히고, {@code claimed_at IS NULL}만 보면 끝난 것도 잡힌다.</li>
     * <li>{@code ORDER BY created_at, id} — 먼저 온 것부터. 동시 접수로 {@code created_at}이 같은
     *     밀리초일 수 있어 {@code id}로 순서를 확정한다.</li>
     * <li>{@code FOR UPDATE} — 이 행에 쓰기 잠금. 트랜잭션이 끝날 때까지 다른 쪽은 못 건드린다.</li>
     * <li>{@code SKIP LOCKED} — 다른 트랜잭션이 이미 잠근 행은 기다리지 말고 건너뛴다. 이게 없으면
     *     두 번째 Worker는 첫 번째가 커밋할 때까지 대기했다가, 그때는 이미 claim된 뒤라 빈 결과를
     *     받는다. 있으면 곧바로 그다음 미잠금 행을 잡는다.</li>
     * </ul>
     * JPQL은 {@code FOR UPDATE SKIP LOCKED}를 지원하지 않아 native SQL이다.
     *
     * <p>이 쿼리 혼자서는 소유권이 안 생긴다 — 잠금은 트랜잭션이 끝나면 풀리므로,
     * {@link RagResponseClaimService}가 같은 짧은 트랜잭션 안에서 {@link RagResponse#markClaimed}로
     * {@code claimed_at}을 채우고 커밋해야 그 뒤로도 다른 Worker가 못 집는다.
     *
     * <p>왜 잠금 없이 {@code claimed_at}만으로는 부족한가: 두 Worker가 정말 같은 순간에
     * "1번 job의 claimed_at이 NULL이네"를 각자 확인하면, 그 확인 결과를 써서 UPDATE하려는
     * 찰나에 둘 다 같은(NULL) 값을 본 상태라 둘 다 자기가 집은 줄 안다 — "확인하고 쓰는" 그
     * 틈에 경합이 생긴다. {@code FOR UPDATE}는 그 틈에 한 트랜잭션만 행을 보게 만들어 이
     * 경합을 원천 차단하고, {@code claimed_at}은 잠금이 풀린 뒤의 장기 소유권 표시를 맡는다.
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

    /**
     * 앱 재시작 복구 전용(#340 CodeRabbit 리뷰 반영). 이전 프로세스가 claim한 채 완료하지 못하고
     * 죽은 job은 {@code claimed_at}이 채워진 상태로 DB에 남는다 — 이 상태로는
     * {@link #findNextUnclaimedProcessingForUpdate}가 절대 다시 집어주지 않아, 스위퍼의
     * {@code stale-threshold} 강제종료(fallback)만 기다리게 된다. 재시작 직후 한 번,
     * PROCESSING인데 claim만 남아있는 행의 claim을 전부 풀어 새 Worker가 다시 시도할 수 있게
     * 한다. "인스턴스는 항상 1개"라는 이 프로젝트의 전제 위에서만 안전하다 — 이 메서드가
     * 실행되는 시점엔 다른 프로세스가 진짜로 처리 중일 수 없으므로, claim이 남아있는 행은
     * 전부 죽은 이전 프로세스의 흔적이다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.claimedAt = NULL "
        + "WHERE r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING "
        + "AND r.claimedAt IS NOT NULL")
    int releaseAllClaimsOnStartup();

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
     * 기준으로 판단할 때 쓴다. {@code threshold}는 시각 하나(예: "지금-90초")이고,
     * {@code created_at < threshold}(생성 시각이 그보다 이전)는 "생성된 지 90초보다 더
     * 지났다"와 같은 뜻이다 — threshold 자체가 "정확히 90초 전 시점"이라, 그보다 더 과거에
     * 생성된 job은 전부 경과 시간이 90초를 넘긴 것이다. {@code claimed_at} 여부는 보지 않는다
     * — 아직 아무 Worker도 안 집은 job도 접수 후 threshold가 지나면 대상이 된다(큐 대기 시간
     * + 실행 시간 합산). {@link EntityGraph}로 {@code query}/{@code query.user}를 미리
     * fetch하는 이유는 {@code findFirstByStatusOrderByCreatedAtAsc}와 동일하다 — 스위퍼도
     * WebSocket 알림을 보내려면 트랜잭션 밖에서 {@code query.user.email}에 접근해야 한다.
     */
    @EntityGraph(attributePaths = {"query", "query.user"})
    List<RagResponse> findByStatusAndCreatedAtBefore(ResultStatus status, LocalDateTime threshold);

    /**
     * 아직 PROCESSING일 때만 FAILED로 확정한다. 핵심은 {@code WHERE id = ? AND status = PROCESSING}
     * — id만 있으면 무조건 덮어쓰지만, status 조건이 붙어 "누가 먼저 끝냈으면 아무것도 하지 마라"가
     * 된다. 반환값이 영향받은 행 수라 1이면 내가 확정한 것, 0이면 이미 끝나 있어 건너뛴 것이다.
     * 호출자는 이 값으로 WebSocket 알림을 보낼지 결정한다.
     *
     * <p>호출자는 둘이다 — RagJobTimeoutSweeper의 타임아웃 강제 종료와, Worker의 Ollama 호출 실패
     * 처리({@code RagResponseCommandService.completeFailed}). SQL 모양이 같아 공유하며, 어느 쪽이
     * 먼저 끝냈든 나중 쪽은 똑같이 무시돼야 하기 때문이기도 하다(#288).
     *
     * <p>{@code clearAutomatically = true}: 벌크 UPDATE는 영속성 컨텍스트(1차 캐시)를 거치지 않고
     * DB로 바로 간다. 같은 트랜잭션에서 이 엔티티를 이미 읽어 뒀다면 캐시엔 옛 값이 남고, 그 뒤
     * {@code findById}는 DB가 아니라 캐시를 돌려줘 갱신 전 값을 본다. UPDATE 직후 캐시를 비워 이를
     * 막는다(#286 테스트에서 실제로 걸렸던 문제).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED, "
        + "r.answerText = :answerText, r.errorMessage = :errorMessage "
        + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING")
    int forceFailIfProcessing(@Param("id") Long id, @Param("answerText") String answerText,
                               @Param("errorMessage") String errorMessage);

    /**
     * 아직 PROCESSING일 때만 SUCCESS + 생성 결과로 확정한다. {@link #forceFailIfProcessing}의 반대
     * 방향 — 스위퍼가 먼저 FAILED로 끝낸 job을 Worker의 뒤늦은 정상 완료가 덮어쓰는 경합(#288)을
     * 막는다. 0건이면 호출자(RagFacade.processJob)는 citation 저장도 건너뛴다.
     *
     * <p>SET 절의 5개 필드가 완료 시 채우는 전부다 — 옛 {@code markSuccess()}가 하던 일을 SQL이
     * 대신한다. {@code claimed_at}은 건드리지 않아 완료 뒤에도 "언제 집혔는지"가 남는다.
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
