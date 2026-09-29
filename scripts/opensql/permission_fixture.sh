#!/usr/bin/env bash
# Create or remove only this run's four permission-test users on the current primary.
set -euo pipefail

action="${1:?action required: create|remove|status}"
container="${2:?primary container required}"
run_id="${3:?run ID required}"

if [[ "$container" != 'docgrid-node1' ]] || (( EUID != 0 )); then
  echo 'Run as root on the known primary container only.' >&2
  exit 2
fi
if [[ ! "$run_id" =~ ^[a-z0-9][a-z0-9-]{0,23}$ ]]; then
  echo 'Invalid run ID.' >&2
  exit 2
fi

query() {
  /usr/bin/docker exec "$container" sh -c '
    . /var/lib/docgrid/opensql/etc/credentials.env
    export PGPASSWORD="${PG_SUPERUSER_PASSWORD}"
    exec /var/lib/docgrid/opensql/bin/psql -h 127.0.0.1 -U postgres -d docgrid \
      -X -v ON_ERROR_STOP=1 -At -F , -c "$1"
  ' sh "$1"
}

if [[ "$(query 'SELECT pg_is_in_recovery()')" != 'f' ]]; then
  echo 'Known fixture host is not primary; stop instead of writing elsewhere.' >&2
  exit 1
fi

emails="('ha-${run_id}-admin@invalid.example', 'ha-${run_id}-m@invalid.example', 'ha-${run_id}-c1@invalid.example', 'ha-${run_id}-c2@invalid.example')"
case "$action" in
  create)
    # 1. A unique run ID confines writes to four disposable accounts and the existing ADMIN role.
    if [[ "$(query "SELECT count(*) FROM users WHERE email IN $emails")" != '0' ]]; then
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
    # 2. Only the run's IDs and role presence leave the database container.
    query "SELECT split_part(u.email, '@', 1), u.id,
        EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = u.id AND r.code = 'ADMIN')
      FROM users u WHERE u.email IN $emails ORDER BY u.email"
    ;;
  remove)
    # 3. Remove only mappings and users identified by the exact run-scoped email set.
    query "BEGIN;
      DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE email IN $emails);
      DELETE FROM users WHERE email IN $emails;
      COMMIT;" >/dev/null
    echo 'fixture-removed'
    ;;
  *)
    echo 'Unknown fixture action.' >&2
    exit 2
    ;;
esac
