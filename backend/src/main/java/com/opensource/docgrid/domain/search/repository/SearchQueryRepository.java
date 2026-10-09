package com.opensource.docgrid.domain.search.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.search.dto.ConversationQueryProjection;
import com.opensource.docgrid.domain.search.entity.SearchQuery;

public interface SearchQueryRepository extends JpaRepository<SearchQuery, Long> {

    /** 원격 WebSocket 노드가 검색 완료 신호의 수신자만 DB에서 확인한다. */
    @Query("SELECT q.user.email FROM SearchQuery q WHERE q.id = :queryId")
    Optional<String> findUserEmailByQueryId(@Param("queryId") Long queryId);

    /**
     * 대시보드 집계 카드의 최근 검색 요청 수. 기준 시각 이후 생성된 검색 Query를 센다.
     */
    long countByCreatedAtAfter(LocalDateTime since);

    // GET /search/{queryId} 재조회 시, 본인이 요청한 검색인지 소유권을 쿼리 조건으로 바로 걸러낸다.
    Optional<SearchQuery> findByIdAndUser_Id(Long id, Long userId);

    /** 대화 상세 화면에 필요한 질문 필드만 최근순으로 제한 조회한다. */
    @Query("""
        SELECT new com.opensource.docgrid.domain.search.dto.ConversationQueryProjection(
            q.id, q.queryText, q.createdAt
        )
        FROM SearchQuery q
        WHERE q.conversation.id = :conversationId
        ORDER BY q.createdAt DESC
        """)
    List<ConversationQueryProjection> findConversationDetailQueries(
        @Param("conversationId") Long conversationId,
        Pageable pageable
    );

    /**
     * PROCESSING 검색만 FAILED로 확정해 이미 끝난 상태를 뒤늦은 실패가 덮어쓰지 않게 한다.
     *
     * <p>영향받은 행 수로 호출자가 실패 기록 성공 여부를 확인할 수 있으며, 벌크 UPDATE가
     * 영속성 Context를 우회하므로 실행 직후 Context를 비워 오래된 상태 재사용을 막는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE SearchQuery q
        SET q.status = com.opensource.docgrid.domain.search.enums.ResultStatus.FAILED,
            q.errorMessage = :errorMessage,
            q.updatedAt = CURRENT_TIMESTAMP
        WHERE q.id = :queryId
          AND q.status = com.opensource.docgrid.domain.search.enums.ResultStatus.PROCESSING
        """)
    int markFailedIfProcessing(
        @Param("queryId") Long queryId,
        @Param("errorMessage") String errorMessage
    );
}
