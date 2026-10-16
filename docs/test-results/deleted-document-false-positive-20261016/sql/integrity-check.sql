-- 시험 종료 무결성 확인(읽기 전용).
\echo '## version별 Chunk·ACTIVE Vector·Job 수'
SELECT v.id AS version_id, d.id AS document_id, d.status AS document_status, v.status AS version_status,
       (SELECT count(*) FROM document_chunks c WHERE c.document_version_id = v.id) AS chunks,
       (SELECT count(*) FROM embeddings e WHERE e.document_version_id = v.id AND e.status = 'ACTIVE') AS active_vectors,
       (SELECT count(*) FROM embeddings e WHERE e.document_version_id = v.id AND e.status <> 'ACTIVE') AS inactive_vectors,
       (SELECT count(*) FROM embedding_jobs j WHERE j.document_version_id = v.id) AS jobs,
       (SELECT count(*) FROM embedding_jobs j WHERE j.document_version_id = v.id AND j.status IN ('PENDING','PROCESSING')) AS live_jobs
FROM document_versions v JOIN documents d ON d.id = v.document_id ORDER BY v.id;
\echo '## 같은 Chunk·모델 Vector 중복 (0행이어야 함)'
SELECT chunk_id, embedding_model_id, count(*) FROM embeddings GROUP BY 1, 2 HAVING count(*) > 1;
\echo '## version당 live Job 2개 이상 (0행이어야 함)'
SELECT document_version_id, count(*) FROM embedding_jobs WHERE status IN ('PENDING','PROCESSING') GROUP BY 1 HAVING count(*) > 1;
\echo '## Outbox 미완료·실패 (0행이어야 함)'
SELECT event_id, event_type, status, last_error_code FROM sync_outbox_events WHERE status <> 'PROCESSED';
\echo '## Reindex Event ↔ Job 연결'
SELECT o.event_id, o.aggregate_id AS version_id, o.status, j.id AS job_id, j.status AS job_status
FROM sync_outbox_events o LEFT JOIN embedding_jobs j ON j.source_event_id = o.event_id
WHERE o.event_type = 'DOCUMENT_REINDEX_REQUESTED' ORDER BY o.id;
