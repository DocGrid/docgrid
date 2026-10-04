#!/usr/bin/env bash
# Terminate only the verified local primary postmaster; leave Patroni and etcd running.
set -euo pipefail

container="${1:?local DB container required}"
run_id="${2:?run ID required}"
if (( EUID != 0 )) || [[ ! "$container" =~ ^docgrid-node[123]$ ]] ||
   [[ ! "$run_id" =~ ^ha[0-9]{3}[a-z0-9-]{0,20}$ ]]; then
  echo 'PROCESS_FAULT_INVALID_INPUT' >&2
  exit 2
fi

# 1. Abort if another node has already become primary.
[[ "$(/usr/local/sbin/docgrid-permission-fixture role "$container" "$run_id")" == primary ]] || {
  echo 'PROCESS_FAULT_NOT_PRIMARY' >&2
  exit 1
}
# 2. Read the postmaster PID from PostgreSQL itself and verify the executable before killing it.
/usr/bin/docker exec "$container" sh -eu -c '
  pid="$(head -n 1 /var/lib/docgrid/opensql/data/pgsql/postmaster.pid)"
  case "$pid" in *[!0-9]*|"") exit 2;; esac
  tr "\000" " " < "/proc/$pid/cmdline" | grep -q "postgres -D /var/lib/docgrid/opensql/data/pgsql"
  kill -9 "$pid"
'
echo 'PROCESS_FAULT_INJECTED postmaster=SIGKILL'
