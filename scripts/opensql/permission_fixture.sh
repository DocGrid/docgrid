#!/usr/bin/env bash
# Guard and remove only this run's four permission-test users on the current primary.
set -euo pipefail

action="${1:?action required: create|remove|status}"
container="${2:?local DB container required}"
run_id="${3:?run ID required}"
guard_seconds="${4:-900}"

if [[ ! "$container" =~ ^docgrid-node[123]$ ]] || (( EUID != 0 )); then
  echo 'Run as root with one allowlisted local DB container.' >&2
  exit 2
fi
if [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]]; then
  echo 'Invalid run ID.' >&2
  exit 2
fi
if [[ ! "$guard_seconds" =~ ^[0-9]+$ ]] ||
   (( guard_seconds < 300 || guard_seconds > 1800 )); then
  echo 'Fixture guard duration must be 300-1800 seconds.' >&2
  exit 2
fi

unit="docgrid-fixture-reset-${run_id}"
timer="${unit}.timer"
timer_pending() {
  systemctl is-active --quiet "$timer" &&
    [[ "$(systemctl show "$timer" -p NextElapseUSecMonotonic --value)" != 'infinity' ]]
}

query() {
  /usr/bin/docker exec "$container" sh -c '
    . /var/lib/docgrid/opensql/etc/credentials.env
    export PGPASSWORD="${PG_SUPERUSER_PASSWORD}"
    exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d docgrid \
      -X -v ON_ERROR_STOP=1 -At -F , -c "$1"
  ' sh "$1"
}

require_primary() {
  if [[ "$(query 'SELECT pg_is_in_recovery()')" != 'f' ]]; then
    echo 'Local DB is not primary; keep the cleanup timer armed for retry.' >&2
    exit 1
  fi
}

emails="('ha-${run_id}-admin@invalid.example', 'ha-${run_id}-m@invalid.example', 'ha-${run_id}-c1@invalid.example', 'ha-${run_id}-c2@invalid.example')"
case "$action" in
  role)
    # Report only the local database role; the cluster runner refuses multiple primaries.
    if [[ "$(query 'SELECT pg_is_in_recovery()')" == 'f' ]]; then
      echo primary
    else
      echo replica
    fi
    ;;
  stale-count)
    # Refuse another run while an earlier short-lived ADMIN fixture remains.
    query "SELECT count(*) FROM users
      WHERE email ~ '^ha-[a-z0-9]{12}-(admin|m|c1|c2)@invalid[.]example$'"
    ;;
  arm)
    # 1. Arm every candidate VM before creating a fixture; a promoted replica can then clean it.
    command -v systemd-run >/dev/null
    if timer_pending || [[ "$(query "SELECT count(*) FROM users WHERE email IN $emails")" != '0' ]]; then
      echo 'Fixture timer or run-scoped users already exist.' >&2
      exit 1
    fi
    systemd-run --quiet --collect --unit="$unit" --on-active="${guard_seconds}s" \
      --on-unit-active=60s \
      /usr/local/sbin/docgrid-permission-fixture remove "$container" "$run_id" \
      "$guard_seconds" >/dev/null
    timer_pending || { echo 'Independent fixture timer did not become pending.' >&2; exit 1; }
    echo 'fixture-armed'
    ;;
  create)
    # 2. Never create an ADMIN fixture on a replica or without its local removal timer.
    require_primary
    if ! timer_pending || [[ "$(query "SELECT count(*) FROM users WHERE email IN $emails")" != '0' ]]; then
      echo 'Fixture already exists; choose a new run ID.' >&2
      exit 1
    fi
    query "BEGIN;
      INSERT INTO users (email, password_hash, name, status) VALUES
        ('ha-${run_id}-admin@invalid.example', '!disabled!', 'HA test admin', 'ACTIVE'),
        ('ha-${run_id}-m@invalid.example', '!disabled!', 'HA test M', 'ACTIVE'),
        ('ha-${run_id}-c1@invalid.example', '!disabled!', 'HA test C1', 'ACTIVE'),
        ('ha-${run_id}-c2@invalid.example', '!disabled!', 'HA test C2', 'ACTIVE');
      INSERT INTO user_roles (user_id, role_id, assigned_by, assigned_at)
        SELECT u.id, r.id, a.id, CURRENT_TIMESTAMP
        FROM users u CROSS JOIN roles r
        JOIN users a ON a.email = 'ha-${run_id}-admin@invalid.example'
        WHERE u.email IN $emails AND r.code = 'ADMIN';
      COMMIT;" >/dev/null
    query "SELECT split_part(email, '@', 1), id FROM users WHERE email IN $emails ORDER BY email"
    ;;
  status)
    # 3. Only the run's IDs and role presence leave the database container.
    query "SELECT split_part(u.email, '@', 1), u.id,
        EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = u.id AND r.code = 'ADMIN')
      FROM users u WHERE u.email IN $emails ORDER BY u.email"
    ;;
  remove)
    # 4. A replica timer fails and retries; only the current primary removes this run's users.
    require_primary
    query "BEGIN;
      DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email IN $emails);
      DELETE FROM users WHERE email IN $emails;
      COMMIT;" >/dev/null
    echo 'fixture-removed'
    ;;
  cancel)
    # 5. Keep the timer armed unless removal is visible on the current primary.
    if [[ "$(query "SELECT count(*) FROM users WHERE email IN $emails")" != '0' ]]; then
      echo 'Run-scoped users remain; refusing to cancel cleanup.' >&2
      exit 1
    fi
    if systemctl is-active --quiet "$timer"; then systemctl stop "$timer"; fi
    echo 'fixture-cancelled'
    ;;
  guard-status)
    if timer_pending; then armed=true; else armed=false; fi
    printf 'armed=%s fixture_count=%s\n' "$armed" \
      "$(query "SELECT count(*) FROM users WHERE email IN $emails")"
    ;;
  *)
    echo 'Unknown fixture action.' >&2
    exit 2
    ;;
esac
