SELECT
            e.id          AS embedding_id,
            e.chunk_id    AS chunk_id,
            e.document_id AS document_id,
            dc.chunk_text AS chunk_text,
            dc.page_no    AS page_no,
            dc.section_title AS section_title,
            dc.metadata_json AS metadata_json,
            d.title       AS document_title,
            (e.vector <=> (SELECT CAST(:queryVector AS vector))) AS distance
        FROM embeddings e
          JOIN documents d        ON d.id  = e.document_id
          JOIN document_chunks dc ON dc.id = e.chunk_id
        WHERE e.embedding_model_id = :modelId
          AND e.status             = 'ACTIVE'
          AND e.document_id        IN (:permittedIds)
          AND d.deleted_at         IS NULL
          AND d.status             = 'INDEXED'
          AND d.current_version_id = e.document_version_id
        ORDER BY e.vector <=> (SELECT CAST(:queryVector AS vector))
        LIMIT :topK
