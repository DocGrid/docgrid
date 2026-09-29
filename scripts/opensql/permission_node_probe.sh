#!/usr/bin/env bash
# Read-only, fixed SQL probes for the existing three-node permission experiment.
set -euo pipefail

action="${1:?action required: health|role|role-stats|lsn|delay-config}"
container="${2:?container required}"
user_id="${3:-}"

if [[ ! "$container" =~ ^docgrid-node[123]$ ]] || (( EUID != 0 )); then
  echo 'Run as root for a known DocGrid container only.' >&2
  exit 2
fi

query() {
  local database="$1"
  local sql="$2"
  # 1. The superuser password remains in the container's root-only file.
  /usr/bin/docker exec "$container" sh -c '
    . /var/lib/docgrid/opensql/etc/credentials.env
    export PGPASSWORD="${PG_SUPERUSER_PASSWORD}"
    exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres \
      -d "$1" -X -v ON_ERROR_STOP=1 -At -F , -c "$2"
  ' sh "$database" "$sql"
}

case "$action" in
  health)
    # 2. No role names, addresses or credential values are exported.
    query postgres "SELECT pg_is_in_recovery(),
      EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'),
      (SELECT count(*) FROM pg_stat_replication WHERE state = 'streaming'),
      (SELECT count(*) FROM pg_stat_wal_receiver WHERE status = 'streaming')"
    query docgrid "SELECT EXISTS (
      SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements')"
    ;;
  role)
    if [[ ! "$user_id" =~ ^[1-9][0-9]*$ ]]; then
      echo 'A positive numeric test user ID is required.' >&2
      exit 2
    fi
    query docgrid "SELECT EXISTS (
      SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
      WHERE ur.user_id = $user_id AND r.code = 'ADMIN')"
    ;;
  role-stats)
    # 3. Query text is used only inside the server filter; publish IDs and counts.
    query postgres "SELECT queryid, calls FROM pg_stat_statements
      WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'docgrid')
        AND userid = (SELECT oid FROM pg_roles WHERE rolname = 'docgrid_app')
        AND query ILIKE '%user_roles%'
      ORDER BY queryid"
    ;;
  lsn)
    query postgres "SELECT pg_is_in_recovery(),
      CASE WHEN pg_is_in_recovery() THEN pg_last_wal_receive_lsn()::text
           ELSE pg_current_wal_lsn()::text END,
      CASE WHEN pg_is_in_recovery() THEN pg_last_wal_replay_lsn()::text
           ELSE pg_current_wal_lsn()::text END"
    ;;
  delay-config)
    query postgres "SELECT setting, unit, context, source, pending_restart,
      COALESCE(sourcefile, '') FROM pg_settings
      WHERE name = 'recovery_min_apply_delay'"
    ;;
  *)
    echo 'Unknown read-only probe.' >&2
    exit 2
    ;;
esac
