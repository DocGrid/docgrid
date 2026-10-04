#!/usr/bin/env bash
# Isolate Patroni/DCS write availability by using short, password-redacted local SQL transactions.
set -euo pipefail

run_id="${1:?run ID required}"
samples="${2:?sample count required}"
if (( EUID != 0 )) || [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]] ||
   [[ ! "$samples" =~ ^[0-9]+$ ]] || (( samples < 1 || samples > 240 )); then
  echo 'ETCD_PROBE_INVALID_INPUT' >&2
  exit 2
fi

containers="$(/usr/bin/docker ps -q)"
[[ -n "$containers" && "$(printf '%s\n' "$containers" | wc -l | tr -d ' ')" == 1 ]] || {
  echo 'ETCD_PROBE_CONTAINER_COUNT_UNEXPECTED' >&2
  exit 1
}
container="$containers"

printf 'run_id=%s event=started utc=%s planned_samples=%s\n' \
  "$run_id" "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$samples"
passed=0
failed=0
for (( index=1; index<=samples; index++ )); do
  request_id="${run_id}-${index}"
  # 1. Every attempt is one autocommit transaction with a unique synthetic ID.
  if timeout 6 /usr/bin/docker exec "$container" sh -c '
    . /var/lib/docgrid/opensql/etc/credentials.env
    export PGPASSWORD="$PG_SUPERUSER_PASSWORD" PGCONNECT_TIMEOUT=2
    exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d docgrid \
      -X -q -v ON_ERROR_STOP=1 -c "INSERT INTO ha_probe_writes (run_id, request_id) VALUES ('\''$1'\'', '\''$2'\'')"
  ' sh "$run_id" "$request_id" >/dev/null 2>&1; then
    outcome=committed
    (( passed+=1 ))
  else
    outcome=failed_or_unknown
    (( failed+=1 ))
  fi
  # 2. Only allowlisted synthetic metadata is written to the host journal.
  printf 'run_id=%s utc=%s sample=%s outcome=%s\n' \
    "$run_id" "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$index" "$outcome"
  sleep 1
done
printf 'run_id=%s event=finished utc=%s passed=%s failed_or_unknown=%s\n' \
  "$run_id" "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$passed" "$failed"
