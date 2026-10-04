#!/usr/bin/env bash
# Return only aggregate synthetic-row counts from the current writable DB node.
set -euo pipefail

run_id="${1:?run ID required}"
if (( EUID != 0 )) || [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]]; then
  echo 'ETCD_RECONCILE_INVALID_INPUT' >&2
  exit 2
fi

containers="$(/usr/bin/docker ps -q)"
[[ -n "$containers" && "$(printf '%s\n' "$containers" | wc -l | tr -d ' ')" == 1 ]] || {
  echo 'ETCD_RECONCILE_CONTAINER_COUNT_UNEXPECTED' >&2
  exit 1
}

# 1. Authentication stays inside the DB container; no secret enters stdout or the host journal.
result="$(/usr/bin/docker exec "$containers" sh -c '
  . /var/lib/docgrid/opensql/etc/credentials.env
  export PGPASSWORD="$PG_SUPERUSER_PASSWORD" PGCONNECT_TIMEOUT=2
  exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d docgrid \
    -X -At -v ON_ERROR_STOP=1 -c "SELECT pg_is_in_recovery(), count(*), count(DISTINCT request_id) FROM ha_probe_writes WHERE run_id = '\''$1'\''"
' sh "$run_id" 2>/dev/null)" || {
  echo 'ETCD_RECONCILE_QUERY_FAILED' >&2
  exit 1
}

# 2. Print only validated role and numeric aggregates, never SQL errors or row contents.
[[ "$result" =~ ^([tf])\|([0-9]+)\|([0-9]+)$ ]] || {
  echo 'ETCD_RECONCILE_UNEXPECTED_RESULT' >&2
  exit 1
}
printf 'run_id=%s in_recovery=%s rows=%s distinct_request_ids=%s\n' \
  "$run_id" "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
