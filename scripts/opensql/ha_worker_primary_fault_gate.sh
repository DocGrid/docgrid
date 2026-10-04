#!/usr/bin/env bash
# Inject a guarded PostgreSQL process fault only while this synthetic run has active Worker work.
set -euo pipefail

container="${1:?local DB container required}"
run_id="${2:?synthetic run ID required}"
timeout_seconds="${3:-90}"
if (( EUID != 0 )) || [[ ! "$container" =~ ^docgrid-node[123]$ ]] ||
   [[ ! "$run_id" =~ ^ha[0-9]{3}[a-z0-9-]{0,20}$ ]] ||
   [[ ! "$timeout_seconds" =~ ^[0-9]+$ ]] ||
   (( timeout_seconds < 1 || timeout_seconds > 180 )); then
  echo 'WORKER_FAULT_GATE_INVALID_INPUT' >&2
  exit 2
fi

# 1. The primary and run identifier are fixed before polling; never inject on a standby.
[[ "$(/usr/local/sbin/docgrid-permission-fixture role "$container" "$run_id")" == primary ]] || {
  echo 'WORKER_FAULT_GATE_NOT_PRIMARY' >&2
  exit 1
}
deadline=$((SECONDS + timeout_seconds))
while (( SECONDS < deadline )); do
  # 2. Query only the synthetic run's durable PROCESSING jobs; do not print DB credentials or titles.
  processing="$(/usr/bin/docker exec "$container" sh -c '
    . /var/lib/docgrid/opensql/etc/credentials.env
    export PGPASSWORD="$PG_SUPERUSER_PASSWORD"
    exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d docgrid \
      -X -A -t -v ON_ERROR_STOP=1 -c "$1"
  ' sh "SELECT count(*) FROM embedding_jobs ej
    JOIN document_versions dv ON dv.id = ej.document_version_id
    JOIN documents d ON d.current_version_id = dv.id
    WHERE d.title LIKE '$run_id-%' AND ej.status = 'PROCESSING'" 2>/dev/null)" || {
    echo 'WORKER_FAULT_GATE_QUERY_FAILED' >&2
    exit 1
  }
  if [[ "$processing" =~ ^[0-9]+$ ]] && (( processing > 0 )); then
    # 3. Recheck the leader in the existing fault script immediately before SIGKILL.
    echo "WORKER_FAULT_GATE_TRIGGERED processing=$processing observed_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    /tmp/ha_primary_process_fault.sh "$container" "$run_id"
    exit 0
  fi
  sleep 0.2
done
echo 'WORKER_FAULT_GATE_TIMEOUT_NO_INJECTION' >&2
exit 1
