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
 * 걸고, Worker의 완료 확정은 상태·현재 소유권 토큰·lease 조건부 UPDATE로 한다. 이 프로젝트엔
 * {@code @Version}이 없어 엔티티를 불러와 save()하면 나중 쓰기가 무조건 이기는데, SQL의 WHERE절이
 * "먼저 끝난 결과를 덮어쓰지 마라"를 대신한다.
 *
 * <pre>
 * 메서드                                  호출자          시점
 * findNextUnclaimedProcessingForUpdate    Claim 서비스    폴링마다 (job 집기)
 * findWithQueryAndUserById                Worker          claim 직후 (알림용 email)
 * renewClaimLease                       Worker          살아 있는 작업의 lease 연장
 * findByStatusAndCreatedAtBefore          Sweeper         15초마다 (90초 넘은 job 탐색)
 * forceFailIfProcessing                   Sweeper+Worker  FAILED 확정
 * completeSuccessIfOwned                 Worker          SUCCESS 확정
 * </pre>
 */
public interface RagResponseRepository extends JpaRepository<RagResponse, Long> {

    /**
     * 대기 중인 job 중 가장 오래된 것 하나를 골라 행 잠금을 걸고 가져온다(#340). 절별 의미:
     * <ul>
     * <li>PROCESSING이고 선점되지 않았거나 lease가 만료된 행만 대상이다. 유효한 claim은
     *     다른 앱이 시작하더라도 건드리지 않는다.</li>
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
     * 새 token과 lease를 기록하고 커밋해야 그 뒤로도 다른 Worker가 못 집는다.
     *
     * <p>잠금 없이 만료 여부를 읽고 token을 쓰면 두 Worker가 동시에 만료된 같은 행을 보고
     * 둘 다 소유권을 얻었다고 오인할 수 있다. {@code FOR UPDATE}가 claim 단계의 경합을 막고,
     * 커밋 뒤에는 token·lease가 장기 소유권 표시를 맡는다.
     */
    @Query(value = """
        SELECT * FROM rag_responses
        WHERE status = 'PROCESSING'
          AND (claim_token IS NULL OR claim_lease_until < :now)
        ORDER BY created_at ASC, id ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<RagResponse> findNextUnclaimedProcessingForUpdate(@Param("now") LocalDateTime now);

    /**
     * claim된 job을 실제로 처리하는 Worker 스레드가 쓰는 조회. {@code query}/{@code query.user}를
     * {@link EntityGraph}로 미리 fetch해, 처리가 끝난 뒤(WebSocket push 시점) 트랜잭션 밖에서
     * {@code job.getQuery().getUser().getEmail()}에 접근해도 {@code LazyInitializationException}이
     * 나지 않게 한다. 기존 {@code findById}는 그대로 두고 이름을 다르게 둔 이유는, {@code RagFacade}가
     * 이미 쓰고 있는 평범한 {@code findById(jobId)} 호출의 동작을 이번 변경으로 건드리지 않기 위함이다.
     */
    @EntityGraph(attributePaths = {"query", "query.user"})
    Optional<RagResponse> findWithQueryAndUserById(Long id);

    /** 현재 owner·token의 lease가 아직 유효할 때만 연장한다. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.claimLeaseUntil = :leaseUntil "
        + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING "
        + "AND r.claimOwner = :owner AND r.claimToken = :token AND r.claimLeaseUntil > :now")
    int renewClaimLease(@Param("id") Long id, @Param("owner") String owner,
                        @Param("token") String token, @Param("now") LocalDateTime now,
                        @Param("leaseUntil") LocalDateTime leaseUntil);

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
     * <p>이 조건은 전역 90초 타임아웃 스위퍼에만 허용한다. Worker의 실패 처리는
     * {@link #completeFailureIfOwned}로 현재 token을 추가 검사한다.
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

    /** Worker 실패 결과는 현재 소유권 토큰과 살아 있는 lease가 모두 맞을 때만 확정한다. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED, "
        + "r.answerText = :answerText, r.errorMessage = :errorMessage "
        + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING "
        + "AND r.claimToken = :token AND r.claimLeaseUntil > :now")
    int completeFailureIfOwned(@Param("id") Long id, @Param("token") String token,
                               @Param("now") LocalDateTime now, @Param("answerText") String answerText,
                               @Param("errorMessage") String errorMessage);

    /** 이전 token의 늦은 성공은 행을 바꾸지 않는다. 인용도 이 UPDATE가 1행일 때만 저장한다. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RagResponse r SET r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.SUCCESS, "
        + "r.answerText = :answerText, r.llmModelName = :llmModelName, "
        + "r.inputTokenCount = :inputTokenCount, r.outputTokenCount = :outputTokenCount, "
        + "r.latencyMs = :latencyMs "
        + "WHERE r.id = :id AND r.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING "
        + "AND r.claimToken = :token AND r.claimLeaseUntil > :now")
    int completeSuccessIfOwned(@Param("id") Long id, @Param("token") String token,
                               @Param("now") LocalDateTime now, @Param("answerText") String answerText,
                               @Param("llmModelName") String llmModelName,
                               @Param("inputTokenCount") Integer inputTokenCount,
                               @Param("outputTokenCount") Integer outputTokenCount,
                               @Param("latencyMs") Integer latencyMs);
}
