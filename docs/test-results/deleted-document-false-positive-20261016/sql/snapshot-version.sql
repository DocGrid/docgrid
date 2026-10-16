-- 사용법: psql -v vid=<document_version_id> -f snapshot-version.sql
-- 한 문서 버전의 원장·Job·Chunk·Vector·Outbox·Issue 상태를 읽기 전용으로 조회한다.
\echo '## document / version'
SELECT d.id AS document_id, d.status AS document_status, d.current_version_id, d.deleted_at IS NOT NULL AS deleted,
       v.id AS version_id, v.status AS version_status, v.indexed_at, v.updated_at AS version_updated_at
FROM document_versions v JOIN documents d ON d.id = v.document_id WHERE v.id = :vid;
\echo '## embedding_jobs'
SELECT j.id, j.status, j.retry_count, j.source_event_id, j.created_at, j.completed_at,
       (SELECT count(*) FROM embedding_job_attempts a WHERE a.embedding_job_id = j.id) AS attempts,
       (SELECT count(*) FROM indexing_events e WHERE e.embedding_job_id = j.id) AS indexing_events
FROM embedding_jobs j WHERE j.document_version_id = :vid ORDER BY j.id;
\echo '## chunks / embeddings (count by model, status)'
SELECT (SELECT count(*) FROM document_chunks c WHERE c.document_version_id = :vid) AS chunk_count,
       e.embedding_model_id, e.status, count(*) AS embedding_count
FROM embeddings e WHERE e.document_version_id = :vid GROUP BY e.embedding_model_id, e.status ORDER BY 2, 3;
\echo '## embeddings (id, chunk, hash)'
SELECT e.id AS embedding_id, c.chunk_index, e.chunk_id, e.status, e.vector_hash,
       md5(e.vector::text) AS vector_text_md5, e.created_at
FROM embeddings e JOIN document_chunks c ON c.id = e.chunk_id
WHERE e.document_version_id = :vid ORDER BY c.chunk_index, e.id;
\echo '## sync_outbox_events (aggregate = version or document)'
SELECT o.event_id, o.event_type, o.aggregate_type, o.aggregate_id, o.status, o.retry_count, o.idempotency_key,
       o.occurred_at, o.processed_at, o.last_error_code
FROM sync_outbox_events o
WHERE (o.aggregate_type = 'DOCUMENT_VERSION' AND o.aggregate_id = :vid)
   OR (o.aggregate_type = 'DOCUMENT' AND o.aggregate_id = (SELECT document_id FROM document_versions WHERE id = :vid))
ORDER BY o.id;
\echo '## sync_consistency_issues (version or document)'
SELECT i.id, i.issue_type, i.severity, i.status, i.repairable, i.repair_event_id, i.repair_attempt_count,
       i.detected_at, i.last_detected_at, i.resolved_at, i.actual_json
FROM sync_consistency_issues i
WHERE i.document_version_id = :vid ORDER BY i.id;
