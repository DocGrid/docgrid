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
}
