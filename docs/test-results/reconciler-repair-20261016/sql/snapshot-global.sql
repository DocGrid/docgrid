-- 전역 요약: 활성 모델, 문서·버전 상태, Issue·Outbox 집계, 최근 실행 이력.
\echo '## active embedding model'
SELECT id, model_name, is_active, is_searchable FROM embedding_models ORDER BY id;
\echo '## documents by status'
SELECT d.status, count(*) FROM documents d GROUP BY 1 ORDER BY 1;
\echo '## versions by status'
SELECT v.status, count(*) FROM document_versions v GROUP BY 1 ORDER BY 1;
\echo '## issues by type/status'
SELECT issue_type, severity, status, repairable, count(*) FROM sync_consistency_issues GROUP BY 1,2,3,4 ORDER BY 1,3;
\echo '## outbox by type/status'
SELECT event_type, status, count(*) FROM sync_outbox_events GROUP BY 1,2 ORDER BY 1,2;
\echo '## reconciliation runs (latest 10)'
SELECT run_id, mode, status, start_cursor, end_cursor, scanned_count, detected_count, repair_requested_count, started_at, completed_at, error_code
FROM sync_reconciliation_runs ORDER BY id DESC LIMIT 10;
