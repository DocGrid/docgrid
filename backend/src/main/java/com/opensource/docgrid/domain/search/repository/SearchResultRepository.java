package com.opensource.docgrid.domain.search.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.search.dto.ConversationSearchResultProjection;
import com.opensource.docgrid.domain.search.entity.SearchResult;

public interface SearchResultRepository extends JpaRepository<SearchResult, Long> {

    /**
     * 검색 결과와 후보 DTO 변환에 필요한 청크·버전·문서를 검색 순서대로 한 번에 조회한다.
     *
     * <p>RAG Worker와 답변 재조회는 {@code VectorSearchCandidate.from()}에서
     * {@code result.chunk.documentVersion.document} 경로를 모두 읽는다. 이 단일 연관관계 경로를
     * 함께 fetch해 결과마다 추가 SELECT가 발생하거나 조회 세션 밖 변환이 실패하는 것을 막는다.
     */
    @Query("""
        SELECT r
        FROM SearchResult r
        JOIN FETCH r.chunk c
        JOIN FETCH c.documentVersion v
        JOIN FETCH v.document d
        WHERE r.query.id = :queryId
        ORDER BY r.rankNo
        """)
    List<SearchResult> findByQuery_IdOrderByRankNo(@Param("queryId") Long queryId);

    /** 여러 대화 Turn의 검색 결과 표시 필드를 queryId와 rank 순서로 한 번에 조회한다. */
    @Query("""
        SELECT new com.opensource.docgrid.domain.search.dto.ConversationSearchResultProjection(
            r.query.id, r.rankNo, d.id, c.id, d.title, c.chunkText, c.pageNo, r.similarityScore,
            c.sectionTitle, c.metadataJson
        )
        FROM SearchResult r
        JOIN r.chunk c
        JOIN c.documentVersion v
        JOIN v.document d
        WHERE r.query.id IN :queryIds
        ORDER BY r.query.id, r.rankNo
        """)
    List<ConversationSearchResultProjection> findConversationDetailResults(
        @Param("queryIds") List<Long> queryIds
    );
}
