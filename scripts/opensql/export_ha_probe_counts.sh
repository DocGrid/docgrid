#!/usr/bin/env bash
# Export only synthetic per-request row counts from the verified primary.
set -euo pipefail

container="${1:?primary DB container required}"
run_id="${2:?run ID required}"
if (( EUID != 0 )) || [[ ! "$container" =~ ^docgrid-node[123]$ ]] ||
   [[ ! "$run_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$ ]]; then
  echo 'DB_EXPORT_INVALID_INPUT' >&2
  exit 2
fi

query() {
  /usr/bin/docker exec "$container" sh -c '
    . /var/lib/docgrid/opensql/etc/credentials.env
    export PGPASSWORD="$PG_SUPERUSER_PASSWORD"
    exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d docgrid \
      -X -v ON_ERROR_STOP=1 -At -c "$1"
  ' sh "$1" 2>/dev/null
}

if [[ "$(query 'SELECT pg_is_in_recovery()')" != f ]]; then
  echo 'DB_EXPORT_NOT_PRIMARY' >&2
  exit 1
fi

sql="COPY (
  SELECT request_id, count(*) AS row_count
  FROM ha_probe_writes
  WHERE run_id = '$run_id'
  GROUP BY request_id ORDER BY request_id
) TO STDOUT WITH CSV HEADER"
if ! query "$sql"; then
  echo 'DB_EXPORT_QUERY_FAILED' >&2
  exit 1
fi
