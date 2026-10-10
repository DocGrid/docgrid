package com.opensource.docgrid.domain.search.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.opensource.docgrid.domain.embedding.entity.Embedding;

/**
 * pgvector 코사인 거리 검색 전용 Repository.
 *
 * <p>queryVector는 float[]를 서비스 레이어에서 '[v1,v2,...]' 문자열로 변환해 전달하며,
 * SQL에서 CAST(:queryVector AS vector)로 pgvector 타입으로 변환한다.
 * permittedIds는 권한 pre-filter(F-SEARCH-04) 결과이며, 빈 목록이면 서비스에서 조기 반환해야 한다.
 */
public interface VectorSearchRepository extends JpaRepository<Embedding, Long> {

    @Query(value = """
        SELECT
            e.id          AS embedding_id,
            e.chunk_id    AS chunk_id,
            e.document_id AS document_id,
            dc.chunk_text AS chunk_text,
            dc.page_no    AS page_no,
            dc.section_title AS section_title,
            dc.metadata_json AS metadata_json,
            d.title       AS document_title,
            (e.vector <=> CAST(:queryVector AS vector)) AS distance
        FROM embeddings e
          JOIN documents d        ON d.id  = e.document_id
          JOIN document_chunks dc ON dc.id = e.chunk_id
        WHERE e.embedding_model_id = :modelId
          AND e.status             = 'ACTIVE'
          AND e.document_id        IN (:permittedIds)
          AND d.deleted_at         IS NULL
          AND d.status             = 'INDEXED'
          AND d.current_version_id = e.document_version_id
        ORDER BY e.vector <=> CAST(:queryVector AS vector)
        LIMIT :topK
        """, nativeQuery = true)
    List<VectorSearchRow> findTopK(
        @Param("queryVector") String queryVector,
        @Param("modelId") Long modelId,
        @Param("permittedIds") List<Long> permittedIds,
        @Param("topK") int topK
    );

    /**
     * 하이브리드 검색의 희귀 단어 가중용 문서 빈도 조회. 질문 단어(정규식 패턴)가 몇 개 청크에 들어 있는지 센다.
     *
     * <p>{@code ord = 0} 행은 검색 범위 전체 청크 수다. 이 값과 단어별 빈도로 희귀할수록 큰 IDF 가중치를 만든다.
     * 벡터 쿼리({@link #findTopK})와 권한·상태 조건을 한 글자도 다르지 않게 적용한다. 이 조건이 달라지면
     * 권한 없는 문서의 단어 분포가 새므로 한쪽만 고치지 말 것. {@code patterns}는 줄바꿈으로 이은 정규식 목록이다.
     */
    @Query(value = """
        SELECT 0 AS ord, COUNT(*) AS match_count
        FROM embeddings e
          JOIN documents d        ON d.id  = e.document_id
          JOIN document_chunks dc ON dc.id = e.chunk_id
        WHERE e.embedding_model_id = :modelId
          AND e.status             = 'ACTIVE'
          AND e.document_id        IN (:permittedIds)
          AND d.deleted_at         IS NULL
          AND d.status             = 'INDEXED'
          AND d.current_version_id = e.document_version_id
        UNION ALL
        SELECT CAST(t.ord AS integer) AS ord, COUNT(c.chunk_id) AS match_count
        FROM unnest(string_to_array(:patterns, chr(10))) WITH ORDINALITY AS t(pat, ord)
          LEFT JOIN LATERAL (
            SELECT dc.id AS chunk_id
            FROM embeddings e
              JOIN documents d        ON d.id  = e.document_id
              JOIN document_chunks dc ON dc.id = e.chunk_id
            WHERE e.embedding_model_id = :modelId
              AND e.status             = 'ACTIVE'
              AND e.document_id        IN (:permittedIds)
              AND d.deleted_at         IS NULL
              AND d.status             = 'INDEXED'
              AND d.current_version_id = e.document_version_id
              AND dc.chunk_text ~* t.pat
          ) c ON TRUE
        GROUP BY t.ord
        ORDER BY ord
        """, nativeQuery = true)
    List<PatternCountRow> countPatternMatches(
        @Param("patterns") String patterns,
        @Param("modelId") Long modelId,
        @Param("permittedIds") List<Long> permittedIds
    );

    /**
     * 하이브리드 검색의 희귀 단어 갈래. 질문 단어를 많이(희귀한 단어일수록 비중이 큼) 가진 청크를 커버리지 순으로 조회한다.
     *
     * <p>커버리지 = (청크에 들어 있는 단어의 가중치 합) / {@code totalWeight}. 커버리지가 {@code minCoverage} 이상인
     * 상위 {@code limit}개와, 벡터 후보 중 하나라도 단어가 겹치는 청크({@code vectorChunkIds})를 함께 돌려준다.
     * 후자는 벡터 후보의 순위 보너스를 같은 규칙으로 계산하기 위한 것이다.
     * 벡터 쿼리({@link #findTopK})와 권한·상태 조건을 한 글자도 다르지 않게 적용한다. 이 조건이 달라지면
     * 권한 없는 문서가 노출되므로 한쪽만 고치지 말 것. {@code patterns}와 {@code weights}는 줄바꿈으로 이은 같은 길이의 목록이다.
     */
    @Query(value = """
        WITH pats AS (
            SELECT t.pat AS pat, t.w AS w
            FROM unnest(
                string_to_array(:patterns, chr(10)),
                CAST(string_to_array(:weights, chr(10)) AS double precision[])
            ) AS t(pat, w)
        ),
        m AS (
            SELECT e.id AS embedding_id,
                   e.chunk_id AS chunk_id,
                   COALESCE((SELECT SUM(p.w) FROM pats p WHERE dc.chunk_text ~* p.pat), 0) / :totalWeight AS coverage
            FROM embeddings e
              JOIN documents d        ON d.id  = e.document_id
              JOIN document_chunks dc ON dc.id = e.chunk_id
            WHERE e.embedding_model_id = :modelId
              AND e.status             = 'ACTIVE'
              AND e.document_id        IN (:permittedIds)
              AND d.deleted_at         IS NULL
              AND d.status             = 'INDEXED'
              AND d.current_version_id = e.document_version_id
              AND dc.chunk_text ~* ANY(string_to_array(:patterns, chr(10)))
        ),
        sel AS (
            (SELECT embedding_id, chunk_id, coverage FROM m
             WHERE coverage >= :minCoverage
             ORDER BY coverage DESC, chunk_id ASC
             LIMIT :limit)
            UNION
            (SELECT embedding_id, chunk_id, coverage FROM m WHERE chunk_id IN (:vectorChunkIds))
        )
        SELECT
            e.id          AS embedding_id,
            e.chunk_id    AS chunk_id,
            e.document_id AS document_id,
            dc.chunk_text AS chunk_text,
            dc.page_no    AS page_no,
            dc.section_title AS section_title,
            dc.metadata_json AS metadata_json,
            d.title       AS document_title,
            (e.vector <=> CAST(:queryVector AS vector)) AS distance,
            sel.coverage  AS coverage
        FROM sel
          JOIN embeddings e       ON e.id  = sel.embedding_id
          JOIN documents d        ON d.id  = e.document_id
          JOIN document_chunks dc ON dc.id = sel.chunk_id
        ORDER BY sel.coverage DESC, e.chunk_id ASC
        """, nativeQuery = true)
    List<LexicalSearchRow> findLexicalCandidates(
        @Param("queryVector") String queryVector,
        @Param("patterns") String patterns,
        @Param("weights") String weights,
        @Param("totalWeight") double totalWeight,
        @Param("minCoverage") double minCoverage,
        @Param("modelId") Long modelId,
        @Param("permittedIds") List<Long> permittedIds,
        @Param("vectorChunkIds") List<Long> vectorChunkIds,
        @Param("limit") int limit
    );
}
