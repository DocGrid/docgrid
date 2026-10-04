#!/usr/bin/env bash
# Export only synthetic run titles and Worker state/counts from the verified primary.
set -euo pipefail

container="${1:?primary DB container required}"
run_id="${2:?synthetic run ID required}"
mode="${3:-documents}"
if (( EUID != 0 )) || [[ ! "$container" =~ ^docgrid-node[123]$ ]] ||
   [[ ! "$run_id" =~ ^[a-z0-9-]{5,40}$ ]] ||
   [[ "$mode" != documents && "$mode" != outbox && "$mode" != model && "$mode" != attempts ]]; then
  echo 'WORKER_EXPORT_INVALID_INPUT' >&2
  exit 2
fi

query() {
  /usr/bin/docker exec "$container" sh -c '
    . /var/lib/docgrid/opensql/etc/credentials.env
    export PGPASSWORD="$PG_SUPERUSER_PASSWORD"
    exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d docgrid \
      -X -v ON_ERROR_STOP=1 --csv -P footer=off -c "$1"
  ' sh "$1" 2>/dev/null
}

# 1. Never mistake a lagging standby for final Worker state.
if [[ "$(query 'SELECT pg_is_in_recovery()' | tail -n 1)" != f ]]; then
  echo 'WORKER_EXPORT_NOT_PRIMARY' >&2
  exit 1
fi

# 2. Only validated synthetic titles leave the VM; credentials and row IDs stay inside.
if [[ "$mode" == outbox ]]; then
  query "SELECT count(*) AS duplicate_outbox_keys FROM (
    SELECT idempotency_key FROM sync_outbox_events
    GROUP BY idempotency_key HAVING count(*) > 1
  ) duplicates"
  exit 0
fi
if [[ "$mode" == model ]]; then
  query "SELECT provider, model_name, dimension, is_active, is_searchable
    FROM embedding_models WHERE is_active AND is_searchable ORDER BY id"
  exit 0
fi
if [[ "$mode" == attempts ]]; then
  query "SELECT d.title AS test_document, a.attempt_no, a.status AS attempt_status,
      coalesce(a.error_code, 'NONE') AS error_code, a.started_at, a.ended_at
    FROM documents d
    JOIN document_versions dv ON dv.id = d.current_version_id
    JOIN embedding_jobs ej ON ej.document_version_id = dv.id
    JOIN embedding_job_attempts a ON a.embedding_job_id = ej.id
    WHERE d.title LIKE '$run_id-%' AND d.deleted_at IS NULL
    ORDER BY d.title, a.attempt_no"
  exit 0
fi

sql="SELECT d.title AS test_document, d.status AS document_status,
  dv.status AS version_status, ej.status AS job_status, ej.retry_count,
  (SELECT count(*) FROM document_chunks dc WHERE dc.document_version_id = dv.id) AS chunks,
  (SELECT count(*) FROM embeddings e WHERE e.document_version_id = dv.id
      AND e.embedding_model_id = ej.embedding_model_id AND e.status = 'ACTIVE') AS embeddings,
  (SELECT count(*) - count(DISTINCT chunk_index) FROM document_chunks dc
      WHERE dc.document_version_id = dv.id) AS duplicate_chunks,
  (SELECT count(*) - count(DISTINCT (chunk_id, embedding_model_id)) FROM embeddings e
      WHERE e.document_version_id = dv.id) AS duplicate_embeddings,
  coalesce(soe.status, 'NO_SOURCE_EVENT') AS source_event_status
FROM documents d
JOIN document_versions dv ON dv.id = d.current_version_id
JOIN embedding_jobs ej ON ej.document_version_id = dv.id
LEFT JOIN sync_outbox_events soe ON soe.event_id = ej.source_event_id
WHERE d.title LIKE '$run_id-%' AND d.deleted_at IS NULL
ORDER BY d.title"
if ! query "$sql"; then
  echo 'WORKER_EXPORT_QUERY_FAILED' >&2
  exit 1
fi
